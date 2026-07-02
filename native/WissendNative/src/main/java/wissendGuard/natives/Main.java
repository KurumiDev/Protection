package wissendGuard.natives;

import wissendGuard.natives.manipulation.StartPlatform;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class Main {


    @CommandLine.Command(name = "native-obfuscator", mixinStandardHelpOptions = true, version = "kodekv123")
    private static class NativeObfuscatorRunner implements Callable<Integer> {
        @CommandLine.Parameters(index = "0")
        private File jarFile;
        @CommandLine.Parameters(index = "1")
        private String outputDirectory;
        @CommandLine.Option(names = {"-l", "--libraries"})
        private File librariesDirectory;
        @CommandLine.Option(names = {"-b", "--black-list"})
        private File blackListFile;
        @CommandLine.Option(names = {"-w", "--white-list"})
        private File whiteListFile;
        @CommandLine.Option(names = {"--plain-lib-name"})
        private String libraryName;
        @CommandLine.Option(names = {"--custom-lib-dir"})
        private String customLibraryDirectory;
        @CommandLine.Option(names = {"-p", "--startPlatform"}, defaultValue = "hotspot")
        private StartPlatform startPlatform;
        @CommandLine.Option(names = {"-a", "--annotations"})
        private boolean useAnnotations;
        @CommandLine.Option(names = {"--debug"})
        private boolean generateDebugJar;

        @Override
        public Integer call() throws Exception {
            List<Path> libs = new ArrayList<>();
            if (librariesDirectory != null) {
                Files.walk(librariesDirectory.toPath(), FileVisitOption.FOLLOW_LINKS)
                        .filter(f -> f.toString().endsWith(".jar") || f.toString().endsWith(".zip"))
                        .forEach(libs::add);
            }

            List<String> blackList = new ArrayList<>();
            if (blackListFile != null) {
                blackList = Files.readAllLines(blackListFile.toPath(), StandardCharsets.UTF_8);
            }

            List<String> whiteList = null;
            if (whiteListFile != null) {
                whiteList = Files.readAllLines(whiteListFile.toPath(), StandardCharsets.UTF_8);
            }

            new Transpilator().process(jarFile.toPath(), Paths.get(outputDirectory),
                    libs, blackList, whiteList, libraryName, customLibraryDirectory, startPlatform, useAnnotations, generateDebugJar);

            return 0;
        }
    }

    public static void main(String[] args) throws IOException {
        System.exit(new CommandLine(new NativeObfuscatorRunner())
                .setCaseInsensitiveEnumValuesAllowed(true).execute(args));
    }

}