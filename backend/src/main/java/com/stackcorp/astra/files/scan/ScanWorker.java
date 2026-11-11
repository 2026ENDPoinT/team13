package com.stackcorp.astra.files.scan;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.files.api.ContentScanner;
import com.stackcorp.astra.files.api.ContentScanner.Clean;
import com.stackcorp.astra.files.api.ContentScanner.Infected;
import com.stackcorp.astra.files.api.ContentScanner.Unavailable;
import com.stackcorp.astra.files.api.ContentScanner.Verdict;
import com.stackcorp.astra.files.scan.ScanRecords.Pending;
import com.stackcorp.astra.files.scan.ScanRecords.Rejected;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

/**
 * quarantined 내용을 검사한다(SC-08, ADR-0018). 한 번에 batchSize개까지 하나씩 SKIP LOCKED와 lease로 잡으므로 worker가 둘이어도 같은
 * 내용을 함께 검사하지 않는다. 검사기에 닿지 못하면 quarantined로 두고 간격을 늘려 다시 시도한다. clean으로 넘기는 길은 검사기의 clean
 * 판정 하나뿐이다. 거부한 내용의 객체는 지운다. 로그에는 blob ID, 시도 수, 사유 코드, signature 이름만 남긴다.
 */
@Component
@Profile("worker")
class ScanWorker {

	private static final Logger log = LoggerFactory.getLogger(ScanWorker.class);

	private final ScanRecords records;

	private final ObjectProvider<ContentScanner> scanner;

	private final ObjectStore store;

	private final TransactionTemplate transaction;

	private final ScanProperties properties;

	private final String owner;

	ScanWorker(ScanRecords records, ObjectProvider<ContentScanner> scanner, ObjectStore store,
			TransactionTemplate transaction, ScanProperties properties) {
		this.records = records;
		this.scanner = scanner;
		this.store = store;
		this.transaction = transaction;
		this.properties = properties;
		this.owner = properties.workerId() != null ? properties.workerId() : defaultOwner();
	}

	@Scheduled(fixedDelayString = "${astra.scan.poll-interval:10s}", initialDelayString = "${astra.scan.poll-interval:10s}")
	void poll() {
		runOnce();
	}

	/** @return 이번에 검사를 시도한 내용 수 */
	int runOnce() {
		ContentScanner active = this.scanner.getIfAvailable();
		int attempted = 0;
		// 검사기가 없으면 quarantined 내용을 그대로 둔다. 다른 판정으로 대신하지 않는다.
		while (active != null && attempted < this.properties.batchSize()) {
			Optional<Pending> claimed = this.transaction
				.execute((tx) -> this.records.claim(this.owner, this.properties.lease()));
			if (claimed == null || claimed.isEmpty()) {
				break;
			}
			attempted++;
			scan(active, claimed.get());
		}
		purgeRejected();
		return attempted;
	}

	private void scan(ContentScanner active, Pending blob) {
		Verdict verdict;
		try {
			verdict = inspect(active, blob);
		}
		catch (RuntimeException ex) {
			log.warn("scan failed blobId={} error={}", blob.blobId(), ex.getClass().getName());
			verdict = new Unavailable("internal");
		}
		try {
			switch (verdict) {
				case Clean c -> {
					if (Boolean.TRUE.equals(this.transaction.execute((tx) -> this.records.markClean(blob.blobId(), this.owner)))) {
						log.info("scan clean blobId={} attempt={}", blob.blobId(), blob.attempt());
					}
					else {
						log.warn("scan result ignored, lease lost blobId={}", blob.blobId());
					}
				}
				case Infected infected -> {
					if (Boolean.TRUE.equals(this.transaction.execute(
							(tx) -> this.records.markInfected(blob.blobId(), this.owner, infected.signature())))) {
						log.warn("malware detected blobId={} signature={}", blob.blobId(), infected.signature());
					}
					else {
						log.warn("scan result ignored, lease lost blobId={}", blob.blobId());
					}
				}
				case Unavailable unavailable -> {
					var delay = this.properties.backoff(blob.attempt());
					this.records.retryLater(blob.blobId(), this.owner, delay);
					log.warn("scanner unavailable blobId={} attempt={} reason={} retryInSeconds={}", blob.blobId(),
							blob.attempt(), unavailable.reason(), delay.toSeconds());
				}
			}
		}
		catch (RuntimeException ex) {
			// 기록하지 못하면 lease가 끝난 뒤 다시 잡힌다.
			log.warn("scan transition failed blobId={} error={}", blob.blobId(), ex.getClass().getName());
		}
	}

	private Verdict inspect(ContentScanner active, Pending blob) {
		Optional<InputStream> content = this.store.open(Bucket.CONTENT, blob.storageKey());
		if (content.isEmpty()) {
			return new Unavailable("missing_object");
		}
		try (InputStream in = content.get()) {
			return active.scan(in, blob.sizeBytes());
		}
		catch (IOException ex) {
			return new Unavailable("io");
		}
	}

	// 거부한 내용은 객체를 지우고 행만 남긴다. 같은 내용을 다시 올리면 행을 보고 바로 거부한다.
	private void purgeRejected() {
		for (Rejected blob : this.records.unpurged(this.properties.batchSize())) {
			try {
				this.store.delete(Bucket.CONTENT, blob.storageKey());
				this.records.markPurged(blob.blobId());
			}
			catch (RuntimeException ex) {
				log.warn("rejected object purge failed blobId={} error={}", blob.blobId(), ex.getClass().getName());
			}
		}
	}

	private static String defaultOwner() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			host = "unknown";
		}
		return host + ":" + ProcessHandle.current().pid();
	}

}
