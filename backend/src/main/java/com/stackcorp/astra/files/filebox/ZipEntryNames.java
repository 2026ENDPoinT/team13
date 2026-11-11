package com.stackcorp.astra.files.filebox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.stackcorp.astra.files.upload.FileNames;

/**
 * ZIP 항목 이름(EP-0263, FILES-08). 압축을 풀 때 다른 경로에 쓰이지 않도록 경로 구분자를 남기지 않는다. 모든 항목은 ZIP의 맨 위에
 * 놓인다.
 * <ul>
 * <li>'/'와 '\'는 '_'로 바꾼다. 따라서 절대 경로와 ".." 경로 조각이 생기지 않는다.</li>
 * <li>그다음 업로드 이름과 같이 정규화한다(제어·방향 문자 제거, 앞뒤 점 제거, 255자). 남는 것이 없으면 "file"이다.</li>
 * <li>대소문자만 다른 이름도 같은 이름으로 보고, 뒤에 오는 것에 " (1)", " (2)" …를 붙인다.</li>
 * </ul>
 * 이름은 UTF-8로 쓰고 ZIP의 UTF-8 이름 flag(general purpose bit 11)를 켠다(ZipOutputStream의 UTF-8 charset).
 */
final class ZipEntryNames {

	private static final String FALLBACK = "file";

	private ZipEntryNames() {
	}

	static List<String> of(List<String> names) {
		List<String> entries = new ArrayList<>(names.size());
		Set<String> taken = new HashSet<>();
		for (String raw : names) {
			String name = FileNames.sanitize(raw == null ? null : raw.replace('/', '_').replace('\\', '_'))
				.orElse(FALLBACK);
			String chosen = null;
			for (String candidate : ItemNames.candidates(name)) {
				if (taken.add(candidate.toLowerCase(Locale.ROOT))) {
					chosen = candidate;
					break;
				}
			}
			// 같은 이름이 접미사 수보다 많다. 겹치지 않는 순번을 앞에 붙인다.
			for (int i = entries.size() + 1; chosen == null; i++) {
				String candidate = i + "-" + name;
				if (taken.add(candidate.toLowerCase(Locale.ROOT))) {
					chosen = candidate;
				}
			}
			entries.add(chosen);
		}
		return entries;
	}

}
