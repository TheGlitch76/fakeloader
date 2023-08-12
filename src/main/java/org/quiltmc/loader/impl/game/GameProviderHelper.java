/*
 * Copyright 2016 FabricMC
 * Copyright 2022-2023 QuiltMC
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

package org.quiltmc.loader.impl.game;


import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipFile;

import org.quiltmc.loader.api.minecraft.Environment;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.util.LoaderUtil;
import org.quiltmc.loader.impl.util.QuiltLoaderInternal;
import org.quiltmc.loader.impl.util.QuiltLoaderInternalType;
import org.quiltmc.loader.impl.util.SystemProperties;
import org.quiltmc.loader.impl.util.UrlConversionException;
import org.quiltmc.loader.impl.util.UrlUtil;

@QuiltLoaderInternal(QuiltLoaderInternalType.LEGACY_NO_WARN)
public final class GameProviderHelper {
	private GameProviderHelper() { }

	public static Path getCommonGameJar() {
		return getGameJar(SystemProperties.GAME_JAR_PATH);
	}

	public static Path getEnvGameJar(Environment env) {
		return getGameJar(env == Environment.CLIENT ? SystemProperties.GAME_JAR_PATH_CLIENT : SystemProperties.GAME_JAR_PATH_SERVER);
	}

	private static Path getGameJar(String property) {
		String val = System.getProperty(property);
		if (val == null) return null;

		Path path = Paths.get(val);
		if (!Files.exists(path)) throw new RuntimeException("Game jar "+path+" ("+LoaderUtil.normalizePath(path)+") configured through "+property+" system property doesn't exist");

		return LoaderUtil.normalizeExistingPath(path);
	}


	public static Optional<Path> getSource(ClassLoader loader, String filename) {
		URL url;

		if ((url = loader.getResource(filename)) != null) {
			try {
				return Optional.of(UrlUtil.getSourcePath(filename, url));
			} catch (UrlConversionException e) {
				// TODO: Point to a logger
				e.printStackTrace();
			}
		}

		return Optional.empty();
	}

	static List<Path> getSources(ClassLoader loader, String filename) {
		try {
			Enumeration<URL> urls = loader.getResources(filename);
			List<Path> paths = new ArrayList<>();

			while (urls.hasMoreElements()) {
				URL url = urls.nextElement();

				try {
					paths.add(UrlUtil.getSourcePath(filename, url));
				} catch (UrlConversionException e) {
					// TODO: Point to a logger
					e.printStackTrace();
				}
			}

			return paths;
		} catch (IOException e) {
			e.printStackTrace();
			return Collections.emptyList();
		}
	}

	public static FindResult findFirst(List<Path> paths, Map<Path, ZipFile> zipFiles, boolean isClassName, String... names) {
		for (String name : names) {
			String file = isClassName ? LoaderUtil.getClassFileName(name) : name;

			for (Path path : paths) {
				if (Files.isDirectory(path)) {
					if (Files.exists(path.resolve(file))) {
						return new FindResult(name, path);
					}
				} else {
					ZipFile zipFile = zipFiles.get(path);

					if (zipFile == null) {
						try {
							zipFile = new ZipFile(path.toFile());
							zipFiles.put(path, zipFile);
						} catch (IOException e) {
							throw new RuntimeException("Error reading "+path, e);
						}
					}

					if (zipFile.getEntry(file) != null) {
						return new FindResult(name, path);
					}
				}
			}
		}

		return null;
	}

	public static final class FindResult {
		public final String name;
		public final Path path;

		FindResult(String name, Path path) {
			this.name = name;
			this.path = path;
		}
	}

	private static boolean emittedInfo = false;



	private static Path getDeobfJarDir(Path gameDir, String gameId, String gameVersion) {
		QuiltLoaderImpl loader = QuiltLoaderImpl.INSTANCE;

		loader.setGameDir(gameDir);
		Path ret = loader.getCacheDir().resolve(QuiltLoaderImpl.CACHE_DIR_NAME).resolve(QuiltLoaderImpl.REMAPPED_JARS_DIR_NAME);

		StringBuilder versionDirName = new StringBuilder();

		if (!gameId.isEmpty()) {
			versionDirName.append(gameId);
		}

		if (!gameVersion.isEmpty()) {
			if (versionDirName.length() > 0) versionDirName.append('-');
			versionDirName.append(gameVersion);
		}

		if (versionDirName.length() > 0) versionDirName.append('-');
		versionDirName.append(QuiltLoaderImpl.VERSION);

		return ret.resolve(versionDirName.toString().replaceAll("[^\\w\\-\\. ]+", "_"));
	}
}
