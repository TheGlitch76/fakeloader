/*
 * Copyright 2016 FabricMC
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

package org.quiltmc.loader.impl.game.minecraft;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.quiltmc.loader.api.ModDependency;
import org.quiltmc.loader.api.ModDependencyIdentifier;
import org.quiltmc.loader.api.Version;
import org.quiltmc.loader.api.VersionRange;
import org.quiltmc.loader.api.minecraft.Environment;
import org.quiltmc.loader.impl.FormattedException;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.entrypoint.GameTransformer;
import org.quiltmc.loader.impl.game.GameProvider;
import org.quiltmc.loader.impl.game.GameProviderHelper;
import org.quiltmc.loader.impl.game.LibClassifier;
import org.quiltmc.loader.impl.game.minecraft.patch.BrandingPatch;
import org.quiltmc.loader.impl.game.minecraft.patch.EntrypointPatch;
import org.quiltmc.loader.impl.launch.common.QuiltLauncher;
import org.quiltmc.loader.impl.metadata.qmj.V1ModMetadataBuilder;
import org.quiltmc.loader.impl.util.Arguments;
import org.quiltmc.loader.impl.util.ExceptionUtil;
import org.quiltmc.loader.impl.util.LoaderUtil;
import org.quiltmc.loader.impl.util.SystemProperties;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;
import org.quiltmc.loader.impl.util.log.LogHandler;

public class MinecraftGameProvider implements GameProvider {
	private static final String[] ALLOWED_EARLY_CLASS_PREFIXES = { "org.apache.logging.log4j.", "com.mojang.util." };

	private static final Set<String> SENSITIVE_ARGS = new HashSet<>(Arrays.asList(
			// all lowercase without --
			"accesstoken",
			"clientid",
			"profileproperties",
			"proxypass",
			"proxyuser",
			"username",
			"userproperties",
			"uuid",
			"xuid"));

	private Environment envType;
	private String entrypoint;
	private Arguments arguments;
	private final List<Path> gameJars = new ArrayList<>(2); // env game jar and common game jar, potentially
	private Path realmsJar;
	private final Set<Path> logJars = new HashSet<>();
	private boolean log4jAvailable;
	private boolean slf4jAvailable;
	private final List<Path> miscGameLibraries = new ArrayList<>(); // libraries not relevant for loader's uses
	private McVersion versionData;
	private boolean useGameJarForLogging;

	private final GameTransformer transformer = new GameTransformer(
			new EntrypointPatch(this),
			new BrandingPatch());

	@Override
	public String getGameId() {
		return "minecraft";
	}

	@Override
	public String getGameName() {
		return "Minecraft";
	}

	@Override
	public String getRawGameVersion() {
		return versionData.getRaw();
	}

	@Override
	public String getNormalizedGameVersion() {
		return versionData.getNormalized();
	}

	@Override
	public Collection<BuiltinMod> getBuiltinMods() {
		V1ModMetadataBuilder metadata = new V1ModMetadataBuilder();
		metadata.id = getGameId();
		metadata.group = "builtin";
		metadata.version = Version.of(getNormalizedGameVersion());
		metadata.name = getGameName();

		if (versionData.getClassVersion().isPresent()) {
			int version = versionData.getClassVersion().getAsInt() - 44;

			Version minJava = Version.of(Integer.toString(version));
			VersionRange range = VersionRange.ofInterval(minJava, true, null, false);
			metadata.depends.add(new ModDependency.Only() {
				@Override
				public boolean shouldIgnore() {
					return false;
				}

				@Override
				public VersionRange versionRange() {
					return range;
				}

				@Override
				public ModDependency unless() {
					return null;
				}

				@Override
				public String reason() {
					return "";
				}

				@Override
				public boolean optional() {
					return false;
				}

				@Override
				public ModDependencyIdentifier id() {
					return ModDependencyIdentifier.of("", "java");
				}
			});
		}

		return Collections.singletonList(new BuiltinMod(gameJars, metadata.build()));
	}

	public Path getGameJar() {
		return gameJars.get(0);
	}

	@Override
	public String getEntrypoint() {
		return entrypoint;
	}

	@Override
	public Path getLaunchDirectory() {
		if (arguments == null) {
			return Paths.get(".");
		}

		return getLaunchDirectory(arguments);
	}

	@Override
	public boolean isEnabled() {
		return System.getProperty(SystemProperties.SKIP_MC_PROVIDER) == null;
	}


	@Override
	public boolean locateGame(QuiltLauncher launcher, String[] args) {
		this.envType = launcher.getEnvironmentType();
		this.arguments = new Arguments();
		arguments.parse(args);

		try {
			LibClassifier<McLibrary> classifier = new LibClassifier<>(McLibrary.class, envType, this);
			McLibrary envGameLib = envType == Environment.CLIENT ? McLibrary.MC_CLIENT : McLibrary.MC_SERVER;
			Path commonGameJar = GameProviderHelper.getCommonGameJar();
			Path envGameJar = GameProviderHelper.getEnvGameJar(envType);
			boolean commonGameJarDeclared = commonGameJar != null;

			if (commonGameJarDeclared) {
				if (envGameJar != null) {
					classifier.process(envGameJar, McLibrary.MC_COMMON);
				}

				classifier.process(commonGameJar);
			} else if (envGameJar != null) {
				classifier.process(envGameJar);
			}

			Set<Path> classpath = new LinkedHashSet<>();

			for (Path path : launcher.getClassPath()) {
				path = LoaderUtil.normalizeExistingPath(path);
				classpath.add(path);
				classifier.process(path);
			}

			if (classifier.has(McLibrary.MC_BUNDLER)) {
				BundlerProcessor.process(classifier);
			}

			envGameJar = classifier.getOrigin(envGameLib);
			if (envGameJar == null) return false;

			commonGameJar = classifier.getOrigin(McLibrary.MC_COMMON);

			if (commonGameJarDeclared && commonGameJar == null) {
				Log.warn(LogCategory.GAME_PROVIDER, "The declared common game jar didn't contain any of the expected classes!");
			}

			gameJars.add(envGameJar);

			if (commonGameJar != null && !commonGameJar.equals(envGameJar)) {
				gameJars.add(commonGameJar);
			}

			// see https://github.com/FabricMC/fabric-loader/pull/793
			Path assetsJar = classifier.getOrigin(McLibrary.MC_ASSETS_ROOT);

			if (assetsJar != null && !assetsJar.equals(commonGameJar) && !assetsJar.equals(envGameJar)) {
				gameJars.add(assetsJar);
			}

			entrypoint = classifier.getClassName(envGameLib);
			realmsJar = classifier.getOrigin(McLibrary.REALMS);
			log4jAvailable = classifier.has(McLibrary.LOG4J_API) && classifier.has(McLibrary.LOG4J_CORE);
			slf4jAvailable = classifier.has(McLibrary.SLF4J_API) && classifier.has(McLibrary.SLF4J_CORE);
			boolean hasLogLib = log4jAvailable || slf4jAvailable;

			Log.configureBuiltin(hasLogLib, !hasLogLib);

			for (McLibrary lib : McLibrary.LOGGING) {
				Path path = classifier.getOrigin(lib);

				if (path != null && !classpath.contains(path)) {
					if (hasLogLib) {
						logJars.add(path);
					} else if (!gameJars.contains(path)) {
						miscGameLibraries.add(path);
					}
				}
			}

			for (Map.Entry<String, McLibrary> lib : McLibrary.MINECRAFT_SPECIFIC.entrySet()) {
				// TODO: Hook up the modid with the transformer cache!
				String modid = lib.getKey();
				Path path = classifier.getOrigin(lib.getValue());

				if (path != null && !gameJars.contains(path)) {
					miscGameLibraries.add(path);
				}
			}

			for (Path path : classifier.getUnmatchedOrigins()) {
				if (!classpath.contains(path)) {
					miscGameLibraries.add(path);
				}
			}
		} catch (IOException e) {
			throw ExceptionUtil.wrap(e);
		}

		String version = arguments.remove(Arguments.GAME_VERSION);
		if (version == null) version = System.getProperty(SystemProperties.GAME_VERSION);
		versionData = McVersionLookup.getVersion(gameJars, entrypoint, version);

		processArgumentMap(arguments, envType);

		return true;
	}

	private static void processArgumentMap(Arguments argMap, Environment envType) {
		switch (envType) {
		case CLIENT:
			if (!argMap.containsKey("accessToken")) {
				argMap.put("accessToken", "QuiltMC");
			}


			if (!argMap.containsKey("version")) {
				argMap.put("version", "Unknown");
			}

			String versionType = "";

			if (argMap.containsKey("versionType") && !argMap.get("versionType").equalsIgnoreCase("release")) {
				versionType = argMap.get("versionType") + "/";
			}

			argMap.put("versionType", versionType + "Quilt Loader " + QuiltLoaderImpl.VERSION);

			if (!argMap.containsKey("gameDir")) {
				argMap.put("gameDir", getLaunchDirectory(argMap).toAbsolutePath().normalize().toString());
			}

			break;
		case DEDICATED_SERVER:
			argMap.remove("version");
			argMap.remove("gameDir");
			argMap.remove("assetsDir");
			break;
		}
	}

	private static Path getLaunchDirectory(Arguments argMap) {
		return Paths.get(argMap.getOrDefault("gameDir", "."));
	}


	@Override
	public void initialize(QuiltLauncher launcher) {
        Map<String, Path> obfJars = new HashMap<>(3);
        String[] names = new String[gameJars.size()];

        for (int i = 0; i < gameJars.size(); i++) {
            String name;

            if (i == 0) {
                name = envType.name().toLowerCase(Locale.ENGLISH);
            } else if (i == 1) {
                name = "common";
            } else {
                name = String.format(Locale.ENGLISH, "extra-%d", i - 2);
            }

            obfJars.put(name, gameJars.get(i));
            names[i] = name;
        }

        if (realmsJar != null) {
            obfJars.put("realms", realmsJar);
        }

        for (Path obf : obfJars.values()) {
            launcher.hideParentPath(obf);
        }


        for (int i = 0; i < gameJars.size(); i++) {
            Path newJar = obfJars.get(names[i]);
            Path oldJar = gameJars.set(i, newJar);

            if (logJars.remove(oldJar)) logJars.add(newJar);
        }

        realmsJar = obfJars.get("realms");

        if (!logJars.isEmpty() && !Boolean.getBoolean(SystemProperties.UNIT_TEST)) {
			for (Path jar : logJars) {
				if (gameJars.contains(jar)) {
					launcher.addToClassPath(jar, ALLOWED_EARLY_CLASS_PREFIXES);
				} else {
					launcher.addToClassPath(jar);
				}
			}
		}

		setupLogHandler(launcher, true);

		transformer.locateEntrypoints(launcher, gameJars);
	}

	private void setupLogHandler(QuiltLauncher launcher, boolean useTargetCl) {
		System.setProperty("log4j2.formatMsgNoLookups", "true"); // lookups are not used by mc and cause issues with older log4j2 versions

		try {
			final String logHandlerClsName;

			if (log4jAvailable) {
				logHandlerClsName = "org.quiltmc.loader.impl.game.minecraft.Log4jLogHandler";
			} else if (slf4jAvailable) {
				logHandlerClsName = "org.quiltmc.loader.impl.game.minecraft.Slf4jLogHandler";
			} else {
				return;
			}

			ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
			Class<?> logHandlerCls;

			if (useTargetCl) {
				Thread.currentThread().setContextClassLoader(launcher.getTargetClassLoader());
				logHandlerCls = launcher.loadIntoTarget(logHandlerClsName);
			} else {
				logHandlerCls = Class.forName(logHandlerClsName);
			}

			Log.init((LogHandler) logHandlerCls.getConstructor().newInstance(), true);
			Thread.currentThread().setContextClassLoader(prevCl);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public Arguments getArguments() {
		return arguments;
	}

	@Override
	public String[] getLaunchArguments(boolean sanitize) {
		if (arguments == null) return new String[0];

		String[] ret = arguments.toArray();
		if (!sanitize) return ret;

		int writeIdx = 0;

		for (int i = 0; i < ret.length; i++) {
			String arg = ret[i];

			if (i + 1 < ret.length
					&& arg.startsWith("--")
					&& SENSITIVE_ARGS.contains(arg.substring(2).toLowerCase(Locale.ENGLISH))) {
				i++; // skip value
			} else {
				ret[writeIdx++] = arg;
			}
		}

		if (writeIdx < ret.length) ret = Arrays.copyOf(ret, writeIdx);

		return ret;
	}

	@Override
	public GameTransformer getEntrypointTransformer() {
		return transformer;
	}

	@Override
	public boolean canOpenGui() {
		if (arguments == null || envType == Environment.CLIENT) {
			return true;
		}

		List<String> extras = arguments.getExtraArgs();
		return !extras.contains("nogui") && !extras.contains("--nogui");
	}

	@Override
	public boolean hasAwtSupport() {
		// MC always sets -XstartOnFirstThread for LWJGL
		return !LoaderUtil.hasMacOs();
	}

	@Override
	public void unlockClassPath(QuiltLauncher launcher) {
		for (Path gameJar : gameJars) {
			if (logJars.contains(gameJar)) {
				launcher.setAllowedPrefixes(gameJar);
			} else {
				launcher.addToClassPath(gameJar);
			}
		}

		if (realmsJar != null) launcher.addToClassPath(realmsJar);

		for (Path lib : miscGameLibraries) {
			launcher.addToClassPath(lib);
		}
	}

	@Override
	public void launch(ClassLoader loader) {
		String targetClass = entrypoint;

		Log.debug(LogCategory.GAME_PROVIDER, "Launching using target class '" + targetClass + "'");

		try {
			Class<?> c = loader.loadClass(targetClass);
			Method m = c.getMethod("main", String[].class);
			m.invoke(null, (Object) arguments.toArray());
		} catch (InvocationTargetException e) {
			throw new FormattedException("Minecraft has crashed!", e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new FormattedException("Failed to start Minecraft", e);
		}
	}
}