package link.e4steam;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftVersionTest {
    @Test
    void acceptsReleaseAndSnapshotNames() {
        assertTrue(MinecraftVersion.isReleaseName("1.21.11"));
        assertTrue(MinecraftVersion.isReleaseName("26.2"));
        assertTrue(MinecraftVersion.isReleaseName("26.3-snapshot-7"));
        assertTrue(MinecraftVersion.isReleaseName("26.3-pre-1"));
        assertTrue(MinecraftVersion.isReleaseName("26.3-rc-2"));
    }

    @Test
    void rejectsUnrelatedVersionStrings() {
        assertFalse(MinecraftVersion.isReleaseName(null));
        assertFalse(MinecraftVersion.isReleaseName("unknown"));
        assertFalse(MinecraftVersion.isReleaseName("26.3-alpha.7"));
        assertFalse(MinecraftVersion.isReleaseName("Fabric Loader 0.19.3"));
    }
}
