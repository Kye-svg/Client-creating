package dev.clicksclient.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.clicksclient.ClicksClient;
import dev.clicksclient.mc.Feature;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.command.CommandSource;

/** .clicks test | clear | diag */
public class ClicksCommand extends Command {
    public ClicksCommand() {
        super("clicks", "Click's Client helpers: test markers, clear flags, show diagnostics.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(context -> {
            ChatUtils.info("Click's Client: .clicks test, .clicks clear, .clicks diag");
            return 1;
        });

        builder.then(literal("test").executes(context -> {
            if (ClicksClient.HUB == null || !ClicksClient.HUB.isActive()) {
                ChatUtils.warning("Turn the Click's Client module on first.");
                return 1;
            }
            int n = ClicksClient.HUB.injectTest();
            if (n == 0) ChatUtils.warning("Every feature is switched off. Turn one on in the Click's Client settings.");
            else ChatUtils.info("Placed a test flag on your chunk for %d feature(s). It disappears after 15 seconds.", n);
            return 1;
        }));

        builder.then(literal("clear").executes(context -> {
            if (ClicksClient.HUB != null) ClicksClient.HUB.clearAll();
            ChatUtils.info("Cleared every flagged chunk.");
            return 1;
        }));

        builder.then(literal("diag").executes(context -> {
            if (ClicksClient.HUB == null) return 1;
            ChatUtils.info("Click's Client diagnostics:");
            for (Feature f : ClicksClient.HUB.features) ChatUtils.info("%s", f.diagLine());
            return 1;
        }));
    }
}
