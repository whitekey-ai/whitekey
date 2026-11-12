package ai.whitekey.cli;

import picocli.CommandLine;

import java.io.File;

public class WhiteKeyCli {

    public static void main(String[] args) {
        File configFile = resolveConfigFile();
        CommandLine cmd = new CommandLine(new WhiteKeyRootCommand());
        cmd.setDefaultValueProvider(new CascadingDefaultProvider(configFile));
        int exitCode = cmd
                .setExecutionExceptionHandler((ex, command, parseResult) -> {
                    command.getErr().println(ex.getMessage());
                    return CommandLine.ExitCode.SOFTWARE;
                })
                .execute(args);
        System.exit(exitCode);
    }

    static class CascadingDefaultProvider implements CommandLine.IDefaultValueProvider {
        private final CommandLine.PropertiesDefaultProvider fileProvider;

        CascadingDefaultProvider(File configFile) {
            this.fileProvider = (configFile != null && configFile.isFile() && configFile.canRead())
                    ? new CommandLine.PropertiesDefaultProvider(configFile)
                    : null;
        }

        @Override
        public String defaultValue(CommandLine.Model.ArgSpec argSpec) throws Exception {
            String key = argSpec.descriptionKey();
            if (key != null && !key.isBlank()) {
                String envVal = System.getenv(key);
                if (envVal != null && !envVal.isBlank()) {
                    return envVal.trim();
                }
            }
            if (fileProvider != null) {
                String fileVal = fileProvider.defaultValue(argSpec);
                if (fileVal != null && !fileVal.isBlank()) {
                    return fileVal.trim();
                }
            }
            return null;
        }
    }

    private static File resolveConfigFile() {
        File localEnv = new File(".env");
        if (localEnv.isFile() && localEnv.canRead()) {
            return localEnv;
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File xdgConfig = new File(userHome, ".config/whitekey/.env");
            if (xdgConfig.isFile() && xdgConfig.canRead()) {
                return xdgConfig;
            }
            File localShare = new File(userHome, ".local/share/whitekey/whitekey.env");
            if (localShare.isFile() && localShare.canRead()) {
                return localShare;
            }
        }
        return localEnv;
    }
}

