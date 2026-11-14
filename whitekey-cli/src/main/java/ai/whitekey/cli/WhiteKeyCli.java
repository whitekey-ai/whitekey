package ai.whitekey.cli;

import ai.whitekey.cli.client.CliConfigDefaults;
import picocli.CommandLine;

import java.io.File;

public class WhiteKeyCli {

    public static void main(String[] args) {
        File configFile = CliConfigDefaults.resolveConfigFile();
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
            String envVal = (key != null && !key.isBlank()) ? System.getenv(key) : null;
            String fileVal = fileProvider != null ? fileProvider.defaultValue(argSpec) : null;
            return CliConfigDefaults.firstNonBlank(envVal, fileVal);
        }
    }
}

