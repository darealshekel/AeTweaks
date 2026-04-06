package com.miningtrackeraddon;

import com.miningtrackeraddon.config.Callbacks;
import com.miningtrackeraddon.config.Configs;
import com.miningtrackeraddon.event.ClientTickHandler;
import com.miningtrackeraddon.event.InputHandler;
import com.miningtrackeraddon.event.RenderHandler;
import com.miningtrackeraddon.event.WorldLoadListener;
import com.miningtrackeraddon.gui.GuiConfigs;

import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.event.RenderEventHandler;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IInitializationHandler;
import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import fi.dy.masa.malilib.registry.Registry;
import fi.dy.masa.malilib.util.data.ModInfo;

public class InitHandler implements IInitializationHandler
{
    @Override
    public void registerModHandlers()
    {
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, new Configs());
        Registry.CONFIG_SCREEN.registerConfigScreenFactory(new ModInfo(Reference.MOD_ID, Reference.MOD_NAME, GuiConfigs::new));

        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        InputEventHandler.getInputManager().registerKeyboardInputHandler(InputHandler.getInstance());
        InputEventHandler.getInputManager().registerMouseInputHandler(InputHandler.getInstance());

        IRenderer renderer = new RenderHandler();
        RenderEventHandler.getInstance().registerGameOverlayRenderer(renderer);
        RenderEventHandler.getInstance().registerWorldLastRenderer(renderer);

        IWorldLoadListener worldLoadListener = new WorldLoadListener();
        WorldLoadHandler.getInstance().registerWorldLoadPreHandler(worldLoadListener);
        WorldLoadHandler.getInstance().registerWorldLoadPostHandler(worldLoadListener);

        TickHandler.getInstance().registerClientTickHandler(new ClientTickHandler());
        Callbacks.init();
    }
}
