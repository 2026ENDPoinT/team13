package com.stackcorp.astra.files.filebox;

import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.files.upload.FileNames;
import com.stackcorp.astra.shared.ApiException;

/**
 * 파일함 항목 이름 규칙. 업로드한 파일 이름과 같은 정규화(files.upload.FileNames)를 쓴다. 이름은 표시와 내려받기에만 쓰고 저장 key로
 * 쓰지 않는다(FILES-10). 같은 폴더 안 활성 항목의 이름은 DB unique 제약(uq_filebox_items_owner_parent_name, 대소문자 구분 없음)이
 * 막는다.
 */
final class ItemNames {

	/** 이름이 겹칠 때 붙여 보는 접미사 수의 상한. */
	static final int MAX_SUFFIX = 99;

	private ItemNames() {
	}

	/**
	 * 사용자가 입력한 폴더 이름이나 새 이름. 경로 구분자와 제어 문자는 받지 않고, 나머지는 업로드 이름처럼 정규화한다.
	 *
	 * @throws ApiException 비었거나 길거나 쓸 수 없는 문자가 있으면 400
	 */
	static String typed(String raw) {
		String name = raw == null ? "" : raw.strip();
		if (name.isEmpty() || name.length() > FileNames.MAX_LENGTH) {
			throw ApiException.invalid("이름은 1자 이상 " + FileNames.MAX_LENGTH + "자 이하여야 합니다.");
		}
		if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid("이름에 쓸 수 없는 문자가 있습니다.");
		}
		return FileNames.sanitize(name).orElseThrow(() -> ApiException.invalid("이름이 올바르지 않습니다."));
	}

	/**
	 * 겹치지 않는 이름을 찾을 때 차례로 써 볼 이름. 처음 것이 원래 이름이고, 그다음은 확장자 앞에 " (1)", " (2)" …를 붙인다. 길이 상한을
	 * 넘지 않도록 본래 이름을 줄인다.
	 */
	static List<String> candidates(String name) {
		List<String> names = new ArrayList<>(MAX_SUFFIX + 1);
		names.add(name);
		String extension = FileNames.extension(name).map((e) -> name.substring(name.length() - e.length() - 1)).orElse("");
		String base = name.substring(0, name.length() - extension.length());
		for (int i = 1; i <= MAX_SUFFIX; i++) {
			String suffix = " (" + i + ")" + extension;
			int end = Math.min(base.length(), FileNames.MAX_LENGTH - suffix.length());
			if (end > 0 && Character.isHighSurrogate(base.charAt(end - 1))) {
				end--;
			}
			names.add(base.substring(0, end).strip() + suffix);
		}
		return names;
	}

}
