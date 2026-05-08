package com.mmm;

import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MMM implements ClientModInitializer
{
    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @Override
    public void onInitializeClient()
    {
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
        LOGGER.info("{} {} initialized", Reference.MOD_NAME, Reference.MOD_VERSION);
    }
}
