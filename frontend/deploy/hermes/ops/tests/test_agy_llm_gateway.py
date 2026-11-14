"""agy-llm-gateway 의 /chat/completions 경로 선택 시험. 실행: python3 -m unittest discover -s deploy/hermes/ops/tests

agy(헤르메스 transport)는 가짜 모듈로, Hive DeepSeek 는 같은 process 의 가짜 HTTP 서버로 바꾼다. 네트워크·agy 없이 돈다.
"""
import importlib.machinery
import importlib.util
import json
import os
import sys
import threading
import types
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / 'agy-llm-gateway'
TOKEN = 'test-token'


class FakeAgy:
    """transport.Client 자리. answer 는 문자열(답)이나 예외."""

    def __init__(self, timeout=60):
        self.answer, self.calls = 'ok', []
        self.command = 'agy'
        self.chat = types.SimpleNamespace(completions=types.SimpleNamespace(create=self.create))

    def create(self, model=None, timeout=None, **kwargs):
        self.calls.append(kwargs)
        if isinstance(self.answer, Exception):
            raise self.answer
        return types.SimpleNamespace(model_dump=lambda: {
            'id': 'agy-1', 'object': 'chat.completion', 'model': model,
            'choices': [{'index': 0, 'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': self.answer}}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 5}})


def fake_transport():
    module = types.ModuleType('transport')
    for name in ('AgyQuotaExhausted', 'AgyLoggedOut', 'AgyMissing'):
        setattr(module, name, type(name, (Exception,), {}))
    module.Client = FakeAgy
    module.resolve_agy = lambda command, env: None
    return module


class DeepSeek(BaseHTTPRequestHandler):
    """Hive DeepSeek 자리. 받은 요청 본문을 남기고 status 로 답한다(200 이면 JSON 답)."""
    protocol_version = 'HTTP/1.1'
    requests = []
    status = 200

    def log_message(self, *_):
        pass

    def do_POST(self):
        DeepSeek.requests.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
        if DeepSeek.status == 200:
            raw = json.dumps({'choices': [{'index': 0, 'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': '{"from":"deepseek"}'}}],
                              'usage': {'prompt_tokens': 20, 'completion_tokens': 7}}).encode()
        else:
            raw = json.dumps({'error': {'message': 'upstream failed'}}).encode()
        self.send_response(DeepSeek.status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


class GatewayRoutingTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.deepseek = ThreadingHTTPServer(('127.0.0.1', 0), DeepSeek)
        threading.Thread(target=cls.deepseek.serve_forever, daemon=True).start()
        os.environ.update(GATEWAY_TOKEN=TOKEN, DEEPSEEK_BASE_URL=f'http://127.0.0.1:{cls.deepseek.server_port}/v3',
                          DEEPSEEK_MODEL='deepseek-test', DEEPSEEK_API_KEY='deepseek-key', LISTEN='127.0.0.1:0')
        sys.modules['transport'] = fake_transport()
        loader = importlib.machinery.SourceFileLoader('agy_llm_gateway', str(SCRIPT))
        spec = importlib.util.spec_from_loader('agy_llm_gateway', loader)
        cls.gw = importlib.util.module_from_spec(spec)
        loader.exec_module(cls.gw)
        cls.logs = []
        cls.gw.log = lambda **fields: cls.logs.append(fields)
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), cls.gw.Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.deepseek.shutdown()
        sys.modules.pop('transport', None)

    def setUp(self):
        self.gw.breaker.reset()
        self.gw.ds_breaker.reset()
        self.gw.client.answer, self.gw.client.calls = 'ok', []
        DeepSeek.requests.clear()
        DeepSeek.status = 200
        self.logs.clear()

    def post(self, body):
        """(status, 답 JSON)."""
        request = urllib.request.Request(f'http://127.0.0.1:{self.server.server_port}/v1/chat/completions',
                                         data=json.dumps(body).encode(), method='POST',
                                         headers={'Authorization': f'Bearer {TOKEN}', 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def route(self):
        routes = [entry for entry in self.logs if 'route' in entry]
        self.assertEqual(len(routes), 1, routes)
        return routes[0]

    @staticmethod
    def summary_request(content, max_tokens=10400):
        """백엔드 쪽별 요약 요청의 모양(json_object, 긴 출력)."""
        return {'model': 'm', 'max_tokens': max_tokens, 'temperature': 0.3, 'stream': False,
                'response_format': {'type': 'json_object'},
                'messages': [{'role': 'system', 'content': 'JSON 하나만 출력합니다.'}, {'role': 'user', 'content': content}]}

    def test_long_json_object_request_takes_the_general_route(self):
        status, answer = self.post(self.summary_request([{'type': 'text', 'text': 'p1'}]))
        self.assertEqual(status, 200)
        self.assertEqual(answer['choices'][0]['message']['content'], '{"from":"deepseek"}')
        self.assertEqual(self.route()['route'], 'deepseek')
        self.assertEqual(DeepSeek.requests[0]['response_format'], {'type': 'json_object'})
        self.assertEqual(DeepSeek.requests[0]['max_tokens'], 10400)

    def test_short_json_object_request_keeps_the_quick_route(self):
        self.post(self.summary_request('도우미 질문', max_tokens=600))
        self.assertEqual(self.route()['route'], 'json-deepseek')

    def test_image_request_goes_to_deepseek_only(self):
        DeepSeek.status = 500
        content = [{'type': 'text', 'text': 'p1'}, {'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,AAAA'}}]
        status, _ = self.post(self.summary_request(content))
        self.assertEqual(status, 502)
        self.assertEqual(self.gw.client.calls, [])
        self.assertEqual(self.route()['route'], 'vision')

    def test_agy_json_answer_is_normalized_when_deepseek_fails(self):
        DeepSeek.status = 500
        self.gw.client.answer = '```json\n{"slides": []}\n```'
        status, answer = self.post(self.summary_request([{'type': 'text', 'text': 'p1'}]))
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(answer['choices'][0]['message']['content']), {'slides': []})
        self.assertEqual(self.route()['route'], 'agy-fallback')

    def test_agy_non_json_answer_to_a_json_object_request_is_not_passed_on(self):
        DeepSeek.status = 500
        self.gw.client.answer = '요약을 쓰려면 이미지가 필요해요.'
        status, _ = self.post(self.summary_request([{'type': 'text', 'text': 'p1'}]))
        self.assertEqual(status, 502)
        log = self.route()
        self.assertEqual((log['route'], log['error']), ('agy-fallback', 'AgyNotJson'))
        self.assertIsNone(self.gw.breaker.skip())

    def test_agy_text_answer_without_json_object_is_passed_on(self):
        DeepSeek.status = 500
        self.gw.client.answer = 'plain text'
        body = self.summary_request([{'type': 'text', 'text': 'p1'}])
        del body['response_format']
        status, answer = self.post(body)
        self.assertEqual(status, 200)
        self.assertEqual(answer['choices'][0]['message']['content'], 'plain text')


if __name__ == '__main__':
    unittest.main()
