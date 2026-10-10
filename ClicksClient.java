package dev.clicksclient;

import com.mojang.logging.LogUtils;
import dev.clicksclient.commands.ClicksCommand;
import dev.clicksclient.mc.ClicksModule;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class ClicksClient extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Click's Client");

    /** The one module that holds every feature. */
    public static ClicksModule HUB;

    @Override
    public void onInitialize() {
        LOG.info("Click's Client loading");
        HUB = new ClicksModule();
        Modules.get().add(HUB);
        Commands.add(new ClicksCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "dev.clicksclient";
    }
}
