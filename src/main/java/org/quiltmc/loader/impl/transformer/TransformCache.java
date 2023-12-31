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

package org.quiltmc.loader.impl.transformer;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.TreeMap;

import org.quiltmc.loader.api.FasterFiles;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.minecraft.MinecraftQuiltLoader;
import org.quiltmc.loader.api.plugin.solver.ModLoadOption;
import org.quiltmc.loader.api.plugin.solver.ModSolveResult;
import org.quiltmc.loader.impl.Data4MixinService;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.discovery.ModResolutionException;
import org.quiltmc.loader.impl.filesystem.PartiallyWrittenIOException;
import org.quiltmc.loader.impl.filesystem.QuiltMapFileSystem;
import org.quiltmc.loader.impl.filesystem.QuiltUnifiedFileSystem;
import org.quiltmc.loader.impl.filesystem.QuiltUnifiedPath;
import org.quiltmc.loader.impl.filesystem.QuiltZipFileSystem;
import org.quiltmc.loader.impl.filesystem.QuiltZipPath;
import org.quiltmc.loader.impl.launch.common.QuiltLauncherBase;
import org.quiltmc.loader.impl.launch.knot.mixin.MixinServiceTransformCache;
import org.quiltmc.loader.impl.launch.knot.mixin.QuiltMixinBootstrap;
import org.quiltmc.loader.impl.launch.knot.mixin.unimportant.MixinServiceKnotBootstrap;
import org.quiltmc.loader.impl.util.FilePreloadHelper;
import org.quiltmc.loader.impl.util.FileSystemUtil;
import org.quiltmc.loader.impl.util.HashUtil;
import org.quiltmc.loader.impl.util.QuiltLoaderInternal;
import org.quiltmc.loader.impl.util.QuiltLoaderInternalType;
import org.quiltmc.loader.impl.util.SystemProperties;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.throwables.IllegalClassLoadError;

@QuiltLoaderInternal(QuiltLoaderInternalType.NEW_INTERNAL)
public class TransformCache {

	static final boolean SHOW_KEY_DIFFERENCE = Boolean.getBoolean(SystemProperties.LOG_CACHE_KEY_CHANGES);

	/** Sub-folder for classes which are not associated with any mod in particular, but still need to be classloaded. */
	public static final String TRANSFORM_CACHE_NONMOD_CLASSLOADABLE = "Unknown Mod";

	private static final String CACHE_FILE = "files.zip";
	private static final String FILE_TRANSFORM_COMPLETE = "__TRANSFORM_COMPLETE";

	private IMixinTransformer mixinTransformer;

	public static TransformCacheResult populateTransformBundle(Path transformCacheFolder, List<ModLoadOption> modList,
		ModSolveResult result) throws ModResolutionException {
		Map<String, String> map = new TreeMap<>();
		// Mod order is important? For now, assume it is
		int index = 0;
		for (ModLoadOption mod : modList) {
			map.put("mod#" + index++, mod.id());
		}

		for (Entry<String, ModLoadOption> provided : result.providedMods().entrySet()) {
			map.put("provided-mod:" + provided.getKey(), provided.getValue().metadata().id());
		}

		for (Entry<String, ModLoadOption> mod : result.directMods().entrySet()) {
			ModLoadOption modOption = mod.getValue();
			try {
				String name = modOption.from().getFileName().toString();
				byte[] hash = modOption.computeOriginHash();
				map.put("mod:" + mod.getKey(), name + " " + HashUtil.hashToString(hash));
			} catch (IOException io) {
				throw new ModResolutionException("Failed to compute the hash of " + modOption, io);
			}
		}

		boolean enableChasm = Boolean.getBoolean(SystemProperties.ENABLE_EXPERIMENTAL_CHASM);
		map.put("system-property:" + SystemProperties.ENABLE_EXPERIMENTAL_CHASM, "" + enableChasm);

		try {
			Files.createDirectories(transformCacheFolder.getParent());
		} catch (IOException e) {
			throw new ModResolutionException("Failed to create parent directories of the transform cache file!", e);
		}

		QuiltZipPath existing = checkTransformCache(transformCacheFolder, map);
		boolean isNewlyGenerated = false;
		if (existing == null) {
			existing = createTransformCache(transformCacheFolder.resolve(CACHE_FILE), toString(map), modList, result);
			isNewlyGenerated = true;
		} else if (!Boolean.getBoolean(SystemProperties.DISABLE_PRELOAD_TRANSFORM_CACHE)) {
			FilePreloadHelper.preLoad(transformCacheFolder.resolve(CACHE_FILE));
		}
		return new TransformCacheResult(transformCacheFolder, isNewlyGenerated, existing);
	}

	private static String toString(Map<String, String> map) {
		StringBuilder optionList = new StringBuilder();
		for (Entry<String, String> entry : map.entrySet()) {
			optionList.append(entry.getKey());
			optionList.append("=");
			optionList.append(entry.getValue());
			optionList.append("\n");
		}
		String options = optionList.toString();
		optionList = null;
		return options;
	}

	private static QuiltZipPath checkTransformCache(Path transformCacheFolder, Map<String, String> options)
		throws ModResolutionException {

		Path cacheFile = transformCacheFolder.resolve(CACHE_FILE);

		if (!FasterFiles.exists(cacheFile)) {
			Log.info(LogCategory.CACHE, "Not reusing previous transform cache since it's missing");
			erasePreviousTransformCache(transformCacheFolder, cacheFile, null);
			return null;
		}

		if (QuiltLoader.isDevelopmentEnvironment()) {
			Log.info(LogCategory.CACHE, "Not reusing previous transform cache since we're in a development environment");
			erasePreviousTransformCache(transformCacheFolder, cacheFile, null);
			return null;
		}

		try (QuiltZipFileSystem fs = new QuiltZipFileSystem("transform-cache", cacheFile, "")) {
			QuiltZipPath inner = fs.getRoot();
			if (!FasterFiles.isRegularFile(inner.resolve(FILE_TRANSFORM_COMPLETE))) {
				Log.info(LogCategory.CACHE, "Not reusing previous transform cache since it's incomplete!");
				erasePreviousTransformCache(transformCacheFolder, cacheFile, null);
				return null;
			}
			Path optionFile = inner.resolve("options.txt");

			try (BufferedReader br = Files.newBufferedReader(optionFile, StandardCharsets.UTF_8)) {
				String line;
				Map<String, String> oldOptions = new TreeMap<>(options);
				Map<String, String> newOptions = new TreeMap<>();
				Map<String, String> differingOptions = new TreeMap<>();
				while ((line = br.readLine()) != null) {
					if (line.isEmpty()) {
						continue;
					}
					int eq = line.indexOf('=');
					String key = line.substring(0, eq);
					String value = line.substring(eq + 1);
					String oldValue = oldOptions.remove(key);
					if (oldValue != null) {
						if (!value.equals(oldValue)) {
							differingOptions.put(key, value);
						}
					} else {
						newOptions.put(key, value);
					}
				}

				if (!oldOptions.isEmpty() || !newOptions.isEmpty() || !differingOptions.isEmpty()) {
					if (SHOW_KEY_DIFFERENCE) {
						Log.info(LogCategory.CACHE, "Not reusing previous transform cache since it has different keys:");

						for (Map.Entry<String, String> old : oldOptions.entrySet()) {
							Log.info(LogCategory.CACHE, "  Missing: '" + old.getKey() + "': '" + old.getValue() + "'");
						}

						for (Map.Entry<String, String> added : newOptions.entrySet()) {
							Log.info(LogCategory.CACHE, "  Included: '" + added.getKey() + "': '" + added.getValue() + "'");
						}

						for (Map.Entry<String, String> diff : differingOptions.entrySet()) {
							String key = diff.getKey();
							String oldValue = diff.getValue();
							String newValue = options.get(key);
							Log.info(
								LogCategory.CACHE, "  Different: '" + key + "': '" + oldValue + "' -> '" + newValue + "'"
							);
						}
					} else {
						Log.info(LogCategory.CACHE, "Not reusing previous transform cache since it has "
							+ (oldOptions.size() + newOptions.size() + differingOptions.size())
							+ " different keys."
							+ " (Add '-Dloader.transform_cache.log_changed_keys=true' to see all changes).");
					}
					erasePreviousTransformCache(transformCacheFolder, cacheFile, null);
					return null;
				}
			}
			return inner;
		} catch (IOException | IOError io) {
			if (io instanceof PartiallyWrittenIOException) {
				Log.info(LogCategory.CACHE, "Not reusing previous transform cache since it's incomplete!");
			} else {
				Log.info(
					LogCategory.CACHE,
					"Not reusing previous transform cache since something went wrong while reading it!"
				);
			}

			erasePreviousTransformCache(transformCacheFolder, cacheFile, io);

			return null;
		}
	}

	private static void erasePreviousTransformCache(Path transformCacheFolder, Path cacheFile, Throwable suppressed)
		throws ModResolutionException {

		if (!Files.exists(transformCacheFolder)) {
			return;
		}

		try {
			Files.walkFileTree(transformCacheFolder, Collections.emptySet(), 1, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			ModResolutionException ex = new ModResolutionException(
				"Failed to read an older transform cache file " + cacheFile + " and then delete it!", e
			);
			if (suppressed != null) {
				ex.addSuppressed(suppressed);
			}
			throw ex;
		}
	}

	static final boolean WRITE_CUSTOM = true;

	private static QuiltZipPath createTransformCache(Path transformCacheFile, String options, List<
		ModLoadOption> modList, ModSolveResult result) throws ModResolutionException {

		try {
			Files.createDirectories(transformCacheFile.getParent());
		} catch (IOException e) {
			throw new ModResolutionException("Failed to create the transform cache parent directory!", e);
		}

		if (false) { // todo
			try (QuiltUnifiedFileSystem fs = new QuiltUnifiedFileSystem("transform-cache", true)) {
				QuiltUnifiedPath root = fs.getRoot();
				populateTransformCache(root, modList, result);
				fs.dumpEntries("after-populate");
				Files.write(root.resolve("options.txt"), options.getBytes(StandardCharsets.UTF_8));
				Files.createFile(root.resolve(FILE_TRANSFORM_COMPLETE));
				QuiltZipFileSystem.writeQuiltCompressedFileSystem(root, transformCacheFile);

				return openCache(transformCacheFile);
			} catch (IOException e) {
				throw new ModResolutionException("Failed to create the transform bundle!", e);
			}
		}

		try (FileSystemUtil.FileSystemDelegate fs = FileSystemUtil.getJarFileSystem(transformCacheFile, true)) {
			URI fileUri = transformCacheFile.toUri();
			URI zipUri = new URI("jar:" + fileUri.getScheme(), fileUri.getPath(), null);

			Path inner = fs.get().getPath("/");

			populateTransformCache(inner, modList, result);

			Files.write(inner.resolve("options.txt"), options.getBytes(StandardCharsets.UTF_8));
			Files.createFile(inner.resolve(FILE_TRANSFORM_COMPLETE));

		} catch (IOException e) {
			throw new ModResolutionException("Failed to create the transform bundle!", e);
		} catch (URISyntaxException e) {
			throw new ModResolutionException(e);
		}

		return openCache(transformCacheFile);
	}

	private static QuiltZipPath openCache(Path transformCacheFile) throws ModResolutionException {
		try {
			QuiltZipPath path = new QuiltZipFileSystem("transform-cache", transformCacheFile, "").getRoot();
			return path;
		} catch (IOException e) {
			// TODO: Better error message for the gui!
			throw new ModResolutionException("Failed to read the newly written transform cache!", e);
		}
	}

	private static void populateTransformCache(Path root, List<ModLoadOption> modList, ModSolveResult solveResult)
		throws ModResolutionException, IOException {
		// Copy everything that's not in the modsToRemap list
		for (ModLoadOption mod : modList) {
			if (mod.namespaceMappingFrom() == null && mod.needsChasmTransforming() && !QuiltLoaderImpl.MOD_ID.equals(mod.id())) {
				final boolean onlyTransformableFiles = mod.couldResourcesChange();
				Path modSrc = mod.resourceRoot();
				Path modDst = root.resolve(mod.id());
				Data4MixinService.resourceRoots.add(modDst);
				try(var stream = Files.walk(modSrc)) {
					stream.forEach(path -> {
						if (!FasterFiles.isRegularFile(path)) {
							// TODO: return space optimizations to transform cache
							// Only copy class files, since those files are the only files modified by chasm
//							return;
						}
						if (onlyTransformableFiles) {
							String fileName = path.getFileName().toString();
							if (!fileName.endsWith(".class") && !fileName.endsWith(".chasm")) {
								// Only copy class files, since those files are the only files modified by chasm
								// (and chasm files, since they are read by chasm)
//								return;
							}
						}
						Path sub = modSrc.relativize(path);
						Path dst = modDst.resolve(sub.toString().replace(modSrc.getFileSystem().getSeparator(), modDst.getFileSystem().getSeparator()));
						try {
							FasterFiles.createDirectories(dst.getParent());
							Files.copy(path, dst);
						} catch (IOException e) {
							throw new Error(e);
						}
					});
				} catch (IOException io) {
					throw new Error(io);
				}
			}
		}

		QuiltMapFileSystem.dumpEntries(root.getFileSystem(), "after-copy");

		InternalsHiderTransform internalsHider = new InternalsHiderTransform(InternalsHiderTransform.Target.MOD);
		Map<Path, ModLoadOption> classes = new HashMap<>();

		// the double read is necessary to avoid storing all classes in memory at once, and thus having memory complexity
		// proportional to mod count

		forEachClassFile(root, modList, (mod, file) -> {
			byte[] classBytes = Files.readAllBytes(file);
			classes.put(file, mod);
			internalsHider.scanClass(mod, file, classBytes);
			return null;
		});

		QuiltMixinBootstrap.init(MinecraftQuiltLoader.getEnvironmentType(), modList.stream().map(ModLoadOption::metadata).toList());
		QuiltLauncherBase.finishMixinBootstrapping();
		var mixinTransformer = MixinServiceTransformCache.getTransformer();

		for (Map.Entry<Path, ModLoadOption> entry : classes.entrySet()) {
			byte[] classBytes = Files.readAllBytes(entry.getKey());
			var key = entry.getKey().toString().replace('/', '.'); // replace / with .
			key = key.substring(key.indexOf('.', 1) + 1); // remove mod id
			key = key.substring(0, key.length() - 6); // remove .class
			try {
				classBytes = mixinTransformer.transformClassBytes(key, key, classBytes);
			} catch (IllegalClassLoadError ignored) {
				// oops, we just tried to transform something mixin won't let us (usually a @Mixin class), so we'll just ignore it
				// we could technically detect this ourselves, but why not let Mixin do it for us
			}
			byte[] newBytes = internalsHider.run(entry.getValue(), classBytes);
			if (newBytes != null) {
				Files.write(entry.getKey(), newBytes);
			}
		}

		internalsHider.finish();
		Data4MixinService.resourceRoots.clear(); // just in case
	}

	private static void forEachClassFile(Path root, List<ModLoadOption> modList, ClassConsumer action)
		throws IOException {
		for (ModLoadOption mod : modList) {
			visitFolder(mod, root.resolve(mod.id()), action);
		}
		visitFolder(null, root.resolve(TRANSFORM_CACHE_NONMOD_CLASSLOADABLE), action);
	}

	private static void visitFolder(ModLoadOption mod, Path root, ClassConsumer action) throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                String folderName = Objects.toString(dir.getFileName());
                if (folderName != null && !couldBeJavaElement(folderName, false)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".class") && couldBeJavaElement(fileName, true)) {
                    byte[] result = action.run(mod, file);
                    if (result != null) {
                        Files.write(file, result);
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            private boolean couldBeJavaElement(String name, boolean ignoreClassSuffix) {
                int end = name.length();
                if (ignoreClassSuffix) {
                    end -= ".class".length();
                }
                for (int i = 0; i < end; i++) {
                    if (name.charAt(i) == '.') {
                        return false;
                    }
                }
                return true;
            }
        });
	}

	@FunctionalInterface
	interface ClassConsumer {
		byte[] run(ModLoadOption mod, Path file) throws IOException;
	}
}
