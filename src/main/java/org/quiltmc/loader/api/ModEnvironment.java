
package org.quiltmc.loader.api;

import org.quiltmc.loader.api.minecraft.Environment;

public enum ModEnvironment {
	CLIENT,
	DEDICATED_SERVER,
	UNIVERSAL;

	public boolean matches(Environment type) {
		switch (this) {
			case CLIENT:
				return type == Environment.CLIENT;
			case DEDICATED_SERVER:
				return type == Environment.DEDICATED_SERVER;
			case UNIVERSAL:
				return true;
			default:
				return false;
		}
	}
}