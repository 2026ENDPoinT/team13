package com.stackcorp.converter;

import java.util.Map;

/**
 * 응답에 쓰는 작은 JSON writer. 이 process는 의존성을 늘리지 않으려고 JSON library를 쓰지 않는다.
 */
final class Json {

	private Json() {
	}

	static String write(Map<String, ?> object) {
		StringBuilder out = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, ?> e : object.entrySet()) {
			if (!first) {
				out.append(',');
			}
			first = false;
			string(out, e.getKey());
			out.append(':');
			Object v = e.getValue();
			if (v == null) {
				out.append("null");
			}
			else if (v instanceof Number || v instanceof Boolean) {
				out.append(v);
			}
			else {
				string(out, v.toString());
			}
		}
		return out.append('}').toString();
	}

	private static void string(StringBuilder out, String s) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20 || c == 0x2028 || c == 0x2029) {
						out.append("\\u%04x".formatted((int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
	}

}
