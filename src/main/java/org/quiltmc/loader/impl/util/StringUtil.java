/*
 * Copyright 2022, 2023 QuiltMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.loader.impl.util;

@QuiltLoaderInternal(QuiltLoaderInternalType.LEGACY_EXPOSED)
public final class StringUtil {

	public static String wrapLines(String str, int limit) {
		if (str.length() < limit) return str;

		StringBuilder sb = new StringBuilder(str.length() + 20);
		int lastSpace = -1;
		int len = 0;

		for (int i = 0, max = str.length(); i <= max; i++) {
			char c = i < max ? str.charAt(i) : ' ';

			if (c == '\r') {
				// ignore
			} else if (c == '\n') {
				lastSpace = sb.length();
				sb.append(c);
				len = 0;
			} else if (Character.isWhitespace(c)) {
				if (len > limit && lastSpace >= 0) {
					sb.setCharAt(lastSpace, '\n');
					len = sb.length() - lastSpace - 1;
				}

				if (i == max) break;

				if (len >= limit) {
					lastSpace = -1;
					sb.append('\n');
					len = 0;
				} else {
					lastSpace = sb.length();
					sb.append(c);
					len++;
				}
			} else if (c == '"' || c == '\'') {
				int next = str.indexOf(c, i + 1) + 1;
				if (next <= 0) next = str.length();
				sb.append(str, i, next);
				len += next - i;
				i = next - 1;
			} else {
				sb.append(c);
				len++;
			}
		}

		return sb.toString();
	}
}
