package com.stackcorp.astra.files.filebox;

import java.util.List;

import com.stackcorp.astra.files.filebox.FileboxController.Ids;
import com.stackcorp.astra.files.filebox.ZipJobs.JobView;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 파일함 ZIP 일괄 다운로드 API(EP-0262 검사, EP-0263 생성, EP-0264 상태의 대체 계약). 작업과 생성물은 요청한 사람만 본다.
 * 링크와 경로에 사용자 ID를 넣지 않는다.
 */
@RestController
class ZipJobController {

	private final ZipJobs zips;

	ZipJobController(ZipJobs zips) {
		this.zips = zips;
	}

	/** ZIP 작업 요청. 202. Idempotency-Key 필수. 같은 항목 집합의 진행 중이거나 내려받을 수 있는 작업이 있으면 그 작업을 돌려준다. */
	@PostMapping("/api/filebox/zip-jobs")
	ResponseEntity<JobView> request(@AuthenticationPrincipal Actor actor, @RequestBody ItemIds body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		List<Long> itemIds = FileboxController.ids(new Ids(body.itemIds()));
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.cacheControl(CacheControl.noStore())
			.body(this.zips.request(actor, itemIds, idempotencyKey));
	}

	@GetMapping("/api/filebox/zip-jobs/{jobId}")
	ResponseEntity<JobView> status(@AuthenticationPrincipal Actor actor, @PathVariable long jobId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.zips.status(actor.userId(), jobId));
	}

	@GetMapping("/api/filebox/zip-jobs/{jobId}/download")
	ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal Actor actor, @PathVariable long jobId) {
		return this.zips.download(actor, jobId);
	}

	record ItemIds(List<Long> itemIds) {
	}

}
