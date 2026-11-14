package ai.whitekey.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;

@Command(name = "setup", description = "Manage setup mode for discovering and approving new rules",
        subcommands = {
                SetupCommand.StatusCommand.class,
                SetupCommand.OnCommand.class,
                SetupCommand.OffCommand.class
        })
public class SetupCommand extends BaseCommand {

    @Command(name = "status", description = "Show whether setup mode is ON or OFF")
    public static class StatusCommand extends BaseCommand {

        @Override
        public void run() {
            boolean enabled = getApiClient().getSetupStatus();
            System.out.println("Setup Mode: " + (enabled ? "ON" : "OFF"));
        }
    }

    @Command(name = "on", description = "Turn setup mode ON")
    public static class OnCommand extends BaseCommand {

        @Option(names = {"--watch"}, description = "Watch incoming actions interactively and turn setup mode OFF on exit")
        private boolean watch;

        @Option(names = {"--interval"}, defaultValue = "300", description = "Poll interval in milliseconds when watching (default: 300)")
        private int pollIntervalMs;

        @Override
        public void run() {
            getApiClient().turnSetupOn();
            System.out.println("Setup Mode: ON");
            if (watch) {
                try {
                    new ApprovalWatcher(getApiClient(), new PrintWriter(System.out, true), pollIntervalMs)
                            .watchInteractive();
                } finally {
                    getApiClient().turnSetupOff();
                    System.out.println("Setup Mode: OFF");
                }
            }
        }
    }

    @Command(name = "off", description = "Turn setup mode OFF and expire remaining unmatched pending actions")
    public static class OffCommand extends BaseCommand {

        @Override
        public void run() {
            getApiClient().turnSetupOff();
            System.out.println("Setup Mode: OFF");
        }
    }
}
