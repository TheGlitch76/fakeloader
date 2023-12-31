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

package org.quiltmc.loader.impl.launch.knot.mixin;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.minecraft.Environment;
import org.quiltmc.loader.api.plugin.ModContainerExt;
import org.quiltmc.loader.api.plugin.ModMetadataExt;
import org.quiltmc.loader.impl.QuiltLoaderImpl;
import org.quiltmc.loader.impl.metadata.qmj.InternalModMetadata;
import org.quiltmc.loader.impl.util.QuiltLoaderInternal;
import org.quiltmc.loader.impl.util.QuiltLoaderInternalType;
import org.quiltmc.loader.impl.util.log.Log;
import org.quiltmc.loader.impl.util.log.LogCategory;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;

@QuiltLoaderInternal(QuiltLoaderInternalType.LEGACY_EXPOSED)
public final class QuiltMixinBootstrap {
	private QuiltMixinBootstrap() { }

	private static boolean initialized = false;

	static void addConfiguration(String configuration) {
		Mixins.addConfiguration(configuration);
	}

	static Set<String> getMixinConfigs(Collection<ModMetadataExt> metadata, Environment type) {
		return metadata.stream()
				.flatMap((m) -> m.mixins(type).stream())
				.filter(s -> s != null && !s.isEmpty())
				.collect(Collectors.toSet());
	}

	public static void init(Environment side, Collection<ModMetadataExt> metadatas) {
		if (initialized) {
			throw new IllegalStateException("QuiltMixinBootstrap has already been initialized!");
		}

		MixinBootstrap.init();
		getMixinConfigs(metadatas, side).forEach(QuiltMixinBootstrap::addConfiguration);

		Map<String, ModMetadataExt> configToModMap = new HashMap<>();


		for (var metadata : metadatas) {
			for (String config : metadata.mixins(side)) {
				// MixinServiceKnot decodes this to load the config from the right mod
				String prefixedConfig = "#" + metadata.id() + ":" + config;
				var prev = configToModMap.putIfAbsent(prefixedConfig, metadata);
				// This will only happen if a mod declares a mixin config *twice*
				if (prev != null) throw new RuntimeException(String.format("Non-unique Mixin config name %s used by the mods %s and %s",
						config, prev.id(), metadata.id()));

				try {
					Mixins.addConfiguration(prefixedConfig);
				} catch (Throwable t) {
					throw new RuntimeException(String.format("Error creating Mixin config %s for mod %s", config, metadata.id()), t);
				}
			}
		}


		try {
			IMixinConfig.class.getMethod("decorate", String.class, Object.class);
			MixinConfigDecorator.apply(configToModMap);
		} catch (NoSuchMethodException e) {
			Log.info(LogCategory.MIXIN, "Detected old Mixin version without config decoration support");
		}

		initialized = true;
	}

	public static final class MixinConfigDecorator {
		static void apply(Map<String, ModMetadataExt> configToModMap) {
			for (Config rawConfig : Mixins.getConfigs()) {
				ModMetadataExt mod = configToModMap.get(rawConfig.getName());
				if (mod == null) continue;

				IMixinConfig config = rawConfig.getConfig();
				config.decorate(FabricUtil.KEY_MOD_ID, mod.id());
				config.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
			}
		}
	}
}
