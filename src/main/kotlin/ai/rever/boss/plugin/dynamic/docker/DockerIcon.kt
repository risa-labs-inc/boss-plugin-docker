package ai.rever.boss.plugin.dynamic.docker

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.SimpleIcons
import compose.icons.simpleicons.Docker

/**
 * The Docker whale, used for the sidebar item, the service tabs, and the
 * empty states — one alias so they can never drift apart.
 *
 * Comes from `simple-icons`, which `buildPluginJar` does NOT bundle; the host's
 * composeApp depends on the same artifact, so the class resolves through the
 * classloader fallback at runtime.
 */
val DockerIcon: ImageVector get() = SimpleIcons.Docker
