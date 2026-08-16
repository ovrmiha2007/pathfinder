package com.lecternscanner;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import org.slf4j.Logger;

@Mod(LecternScannerMod.MODID)
public class LecternScannerMod {
    public static final String MODID = "pathfinder";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LecternScannerMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Pathfinder loaded — by ovrmiha");
    }
}
