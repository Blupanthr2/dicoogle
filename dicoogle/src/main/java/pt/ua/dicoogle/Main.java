/**
 * Copyright (C) 2014  Universidade de Aveiro, DETI/IEETA, Bioinformatics Group - http://bioinformatics.ua.pt/
 *
 * This file is part of Dicoogle/dicoogle.
 *
 * Dicoogle/dicoogle is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Dicoogle/dicoogle is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Dicoogle.  If not, see <http://www.gnu.org/licenses/>.
 */
package pt.ua.dicoogle;

import org.dcm4che2.data.TransferSyntax;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReaderSpi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;
import pt.ua.dicoogle.DicomLog.LogXML;
import pt.ua.dicoogle.core.AsyncIndex;
import pt.ua.dicoogle.core.TagsXML;
import pt.ua.dicoogle.core.settings.ServerSettingsManager;
import pt.ua.dicoogle.plugins.PluginController;
import pt.ua.dicoogle.utils.Platform;
import pt.ua.dicoogle.sdk.settings.server.ServerSettings;
import pt.ua.dicoogle.server.web.auth.Authentication;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Map;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;

/**
 * Main class for Dicoogle
 * @author Filipe Freitas
 * @author Luís A. Bastião Silva <bastiao@ua.pt>
 * @author Samuel Campos <samuelcampos@ua.pt>
 * @author Eduardo Pinho <eduardopinho@ua.pt>
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static Process fsServerProcess = null;


    /**
     * Starts the graphical user interface for Dicoogle
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // System.setProperty("log4j.configurationFile", "log4j-2.xml");
        // PropertyConfigurator.configure("log4j.properties");
        if (Platform.getMode() == Platform.MODE.BUNDLE) {
            File homeDir = new File(Platform.homePath());
            if (!homeDir.exists()) {
                homeDir.mkdir();
            }
        }

        if (args.length == 0) {
            LaunchDicoogle();
            LaunchWebApplication();
        } else {

            if (args[0].equals("-v")) {
                LaunchDicoogle();
                LaunchWebApplication();
            }
            if (args[0].equals("-s")) {
                LaunchDicoogle();
            } else if (args[0].equals("-w") || args[0].equals("--web") || args[0].equals("--webapp")) {
                // open browser
                LaunchDicoogle();
                LaunchWebApplication();
            } else if (args[0].equals("-h") || args[0].equals("--h") || args[0].equals("-help")
                    || args[0].equals("--help")) {
                System.out.println("Dicoogle PACS");
                System.out.println();
                System.out.println(" -s : Start the server");
                System.out.println(" -w : Start the server and load web application in default browser (default)");
                System.out.println(" --fs[=<file>] : Enable filesystem manager (optional config file path)");
            } else {
                System.out.println("Wrong arguments!");
                System.out.println();
                System.out.println("Dicoogle PACS");
                System.out.println("-s : Start the server");
                System.out.println("-w : Start the server and load web application in default browser (default)");
                System.out.println("--fs[=<file>] : Enable filesystem manager (optional config file path)");
            }
        }
        startFileSystemManager(args);
        // Register System Exceptions Hook
        ExceptionHandler.registerExceptionHandler();
    }

    private static boolean LaunchWebApplication() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            logger.warn(
                    "Desktop browsing is not supported in this machine! " + "Request to open web application ignored.");
            return false;
        } else {
            try {
                ServerSettings settings = ServerSettingsManager.getSettings();
                URI uri = URI.create("http://localhost:" + settings.getWebServerSettings().getPort());
                Desktop.getDesktop().browse(uri);
                return true;
            } catch (IOException ex) {
                logger.warn("Request to open web application ignored", ex);
                return false;
            }
        }
    }

    private static void LaunchDicoogle() {
        logger.debug("Starting Dicoogle");
        logger.debug("Loading configuration file: {}", Platform.homePath());

        /* Load all Server Settings */
        try {
            ServerSettingsManager.init();
        } catch (IOException e) {
            logger.error("A critical error occurred: cannot initialize server settings.", e);
            System.exit(-1);
        }
        ServerSettings settings = ServerSettingsManager.getSettings();

        // load tags listings
        try {
            new TagsXML().getXML();
        } catch (SAXException | IOException ex) {
            logger.error(ex.getMessage(), ex);
        }

        // FIXME remove in Dicoogle 4, this loads legacy DICOM Services Log
        new LogXML().getXML();

        /** Verify if it have a defined node */
        if (settings.getArchiveSettings().getNodeName() == null) {
            String hostname = "Dicoogle";

            try {
                InetAddress addr = InetAddress.getLocalHost();

                // Get hostname
                hostname = addr.getHostName();
            } catch (UnknownHostException e) {}

            if (Desktop.isDesktopSupported()) {
                String response = (String) JOptionPane.showInputDialog(null, "What is the name of the machine?",
                        "Enter Node name", JOptionPane.QUESTION_MESSAGE, null, null, hostname);

                settings.getArchiveSettings().setNodeName(response);
            }

            // Save settings
            ServerSettingsManager.saveSettings();
        }

        TransferSyntax.add(new TransferSyntax("1.2.826.0.1.3680043.2.682.1.40", false, false, false, true));
        TransferSyntax.add(new TransferSyntax("1.2.840.10008.1.2.4.70", true, false, false, true));
        TransferSyntax.add(new TransferSyntax("1.2.840.10008.1.2.5.50", false, false, false, true));

        // Initialize authentication and authorization system
        Authentication.getInstance();

        // Initialize platform controller
        PluginController pluginController = PluginController.getInstance();

        // Start the initial Services of Dicoogle
        pt.ua.dicoogle.server.ControlServices.getInstance();

        // Register Image Reader for DICOM Objects
        if (settings.getArchiveSettings().isUseIIORegistry()) {
            IIORegistry.getDefaultInstance().registerServiceProvider(new DicomImageReaderSpi());
            ImageIO.setUseCache(false);
            System.setProperty("dcm4che.useImageIOServiceRegistry", "true");
        }

        // Launch Async Index
        // It monitors a folder, and when a file is touched an event
        // triggers and index is updated.
        if (settings.getArchiveSettings().isDirectoryWatcherEnabled()) {
            AsyncIndex asyncIndex = new AsyncIndex();
        }

        // set up shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            boolean shutdownPlugins = settings.getArchiveSettings().isCallShutdown();

            if (shutdownPlugins) {
                logger.debug("Initiating plugin shutdown sequence...");
                // call shutdown routines
                pluginController.shutdown();
                logger.debug("Plugins were shut down.");
            }
        }));
    }

    private static void startFileSystemManager(String[] args) {
        boolean enabled = false;
        String configPath = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("--fs".equals(arg) || "--filesystem".equals(arg) || "-fs".equals(arg)) {
                enabled = true;
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    configPath = args[++i];
                }
                continue;
            }

            if (arg.startsWith("--fs=") || arg.startsWith("--filesystem=") || arg.startsWith("-fs=")) {
                enabled = true;
                configPath = arg.substring(arg.indexOf('=') + 1).trim();
                continue;
            }
        }

        if (!enabled) {
            logger.info("Filesystem Manager disabled. Use --fs to enable it.");
            return;
        }

        try {
            String os = System.getProperty("os.name").toLowerCase();
            String binaryName;
            String extension = "";

            if (os.contains("win")) {
                binaryName = "fs-server-bundle-win.exe";
                extension = ".exe";
            } else if (os.contains("mac")) {
                binaryName = "fs-server-bundle-macos";
            } else {
                binaryName = "fs-server-bundle-linux";
            }

            // 1. Read binary from the JAR
            InputStream in = Main.class.getResourceAsStream("/dicoogle-next/webapp/dist/bin/" + binaryName);
            if (in == null) {
                logger.error("Could not find filesystem server binary in JAR: {}", binaryName);
                return;
            }

            File tempBin = File.createTempFile("fs-server-", extension);
            tempBin.deleteOnExit(); // Ensure it cleans up when Java exits
            Files.copy(in, tempBin.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tempBin.setExecutable(true);

            // 3. Start the process
            logger.info("Starting Next Filesystem Manager from {}", tempBin.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(tempBin.getAbsolutePath());

            // Pass the port environment variable
            Map<String, String> env = pb.environment();
            env.put("FILESYSTEM_SERVER_PORT", "3333");
            env.put("NODE_ENV", "production");
            applyFsConfigToEnvironment(env, configPath);

            // Inherit IO so you can see console.log outputs from the JS file in your Java console!
            pb.inheritIO();

            fsServerProcess = pb.start();

            // 4. Add a shutdown hook to kill the Node.js process when Dicoogle is closed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (fsServerProcess != null && fsServerProcess.isAlive()) {
                    logger.info("Stopping Filesystem Manager Server...");
                    fsServerProcess.destroy();
                }
            }));

        } catch (Exception e) {
            logger.error("Failed to start embedded FileSystem Manager", e);
        }
    }

    private static void applyFsConfigToEnvironment(Map<String, String> env, String fsConfigPath) {
        if (fsConfigPath == null || fsConfigPath.trim().isEmpty()) {
            return;
        }

        File configFile = new File(fsConfigPath.trim());

        if (!configFile.exists() || !configFile.isFile()) {
            logger.warn("Filesystem config file not found: {}", configFile.getAbsolutePath());
            return;
        }

        String fileName = configFile.getName().toLowerCase();
        if (fileName.endsWith(".json") || fileName.contains(".json.") || looksLikeJsonConfig(configFile)) {
            env.put("FILESYSTEM_CONFIG_PATH", configFile.getAbsolutePath());
            logger.info("Using filesystem JSON config: {}", configFile.getAbsolutePath());
            return;
        }

        String allowedRoots = readAllowedRootsList(configFile);
        if (!allowedRoots.isEmpty()) {
            env.put("FILESYSTEM_ALLOWED_ROOTS", allowedRoots);
            logger.info("Using filesystem roots list from {}", configFile.getAbsolutePath());
        } else {
            logger.warn("Filesystem roots file is empty: {}", configFile.getAbsolutePath());
        }
    }

    private static String readAllowedRootsList(File file) {
        StringBuilder roots = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty()) {
                    continue;
                }

                if (roots.length() > 0) {
                    roots.append(';');
                }
                roots.append(value);
            }
        } catch (IOException ex) {
            logger.warn("Failed to read filesystem roots file {}: {}", file.getAbsolutePath(), ex.getMessage());
            return "";
        }

        return roots.toString();
    }

    private static boolean looksLikeJsonConfig(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty()) {
                    continue;
                }
                return value.startsWith("{") || value.startsWith("[");
            }
        } catch (IOException ex) {
            logger.warn("Failed to inspect filesystem config file {}: {}", file.getAbsolutePath(), ex.getMessage());
        }
        return false;
    }
}

