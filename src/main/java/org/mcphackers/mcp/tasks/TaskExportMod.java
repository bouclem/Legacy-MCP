package org.mcphackers.mcp.tasks;

import static org.mcphackers.mcp.MCPPaths.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

import org.mcphackers.mcp.MCP;
import org.mcphackers.mcp.MCPPaths;
import org.mcphackers.mcp.tools.FileUtil;

/**
 * Task to export the modified Minecraft as a publishable mod JAR.
 * Includes auto-save/backup feature for crash recovery.
 */
public class TaskExportMod extends TaskStaged {

	public static final int STAGE_PREPARE = 0;
	public static final int STAGE_RECOMPILE = 1;
	public static final int STAGE_REOBF = 2;
	public static final int STAGE_PACKAGE = 3;
	public static final int STAGE_FINALIZE = 4;

	private static final String EXPORT_DIR = "export/";
	private static final String EXPORT_BACKUP_DIR = "export/backups/";
	private static final String EXPORT_STATE_FILE = "export/.export_state";

	public TaskExportMod(Side side, MCP instance) {
		super(side, instance);
	}

	@Override
	protected Stage[] setStages() {
		return new Stage[]{
				stage(getLocalizedStage("prepare"), 0, this::prepareExport),
				stage(getLocalizedStage("recompile"), 10, this::recompileSource),
				stage(getLocalizedStage("reobf"), 40, this::reobfuscateClasses),
				stage(getLocalizedStage("package"), 70, this::packageMod),
				stage(getLocalizedStage("finalize"), 90, this::finalizeExport)
		};
	}

	/**
	 * Prepare export directory and check for previous incomplete export
	 */
	private void prepareExport() throws Exception {
		Path exportDir = MCPPaths.get(mcp, EXPORT_DIR);
		Path backupDir = MCPPaths.get(mcp, EXPORT_BACKUP_DIR);
		Path stateFile = MCPPaths.get(mcp, EXPORT_STATE_FILE);

		FileUtil.createDirectories(exportDir);
		FileUtil.createDirectories(backupDir);

		// Check for incomplete previous export
		if (Files.exists(stateFile)) {
			log("Found incomplete previous export, attempting recovery...");
			recoverFromState(stateFile);
		}

		// Save initial state
		saveState("PREPARE", 0);
		log("Export preparation complete");
	}

	/**
	 * Recompile source code with backup
	 */
	private void recompileSource() throws Exception {
		saveState("RECOMPILE", 10);
		createBackup("pre_recompile");

		TaskRecompile recompileTask = new TaskRecompile(side, mcp, this);
		recompileTask.doTask();

		saveState("RECOMPILE_DONE", 39);
		log("Recompilation complete");
	}

	/**
	 * Reobfuscate classes with backup
	 */
	private void reobfuscateClasses() throws Exception {
		saveState("REOBF", 40);
		createBackup("pre_reobf");

		TaskReobfuscate reobfTask = new TaskReobfuscate(side, mcp, this);
		reobfTask.doTask();

		saveState("REOBF_DONE", 69);
		log("Reobfuscation complete");
	}

	/**
	 * Package the mod into a distributable JAR
	 */
	private void packageMod() throws Exception {
		saveState("PACKAGE", 70);

		Side[] sides = side == Side.MERGED ? new Side[]{Side.CLIENT, Side.SERVER} : new Side[]{side};

		for (Side localSide : sides) {
			Path reobfDir = MCPPaths.get(mcp, REOBF_SIDE, localSide);
			Path bin = MCPPaths.get(mcp, BIN, localSide);
			Path exportDir = MCPPaths.get(mcp, EXPORT_DIR);

			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
			String modFileName = String.format("mod_%s_%s.jar", localSide.name, timestamp);
			Path modJar = exportDir.resolve(modFileName);

			// Create the mod JAR with only modified classes
			if (Files.exists(reobfDir)) {
				List<Path> reobfClasses = FileUtil.walkDirectory(reobfDir, path -> !Files.isDirectory(path));

				if (!reobfClasses.isEmpty()) {
					// Create backup before packaging
					createBackup("pre_package_" + localSide.name);

					// Package reobfuscated classes
					FileUtil.compress(reobfDir, modJar);

					// Add non-class resources from bin
					List<Path> assets = FileUtil.walkDirectory(bin, 
						path -> !Files.isDirectory(path) && !path.getFileName().toString().endsWith(".class"));
					if (!assets.isEmpty()) {
						FileUtil.packFilesToZip(modJar, assets, bin);
					}

					log("Created mod JAR: " + modJar.getFileName());
				} else {
					addMessage("No modified classes found for " + localSide.name, WARNING);
				}
			}
		}

		saveState("PACKAGE_DONE", 89);
	}

	/**
	 * Finalize export and cleanup state files
	 */
	private void finalizeExport() throws Exception {
		saveState("FINALIZE", 90);

		Path stateFile = MCPPaths.get(mcp, EXPORT_STATE_FILE);
		Path exportDir = MCPPaths.get(mcp, EXPORT_DIR);

		// Create export info file
		Path infoFile = exportDir.resolve("export_info.txt");
		String info = String.format(
			"Export completed: %s%nSide: %s%nVersion: %s%n",
			LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
			side.name,
			mcp.getCurrentVersion() != null ? mcp.getCurrentVersion().id : "unknown"
		);
		Files.write(infoFile, info.getBytes(java.nio.charset.StandardCharsets.UTF_8));

		// Cleanup state file on success
		Files.deleteIfExists(stateFile);

		log("Export finalized successfully!");
		log("Mod files are in: " + exportDir.toAbsolutePath());
	}

	/**
	 * Save current export state for crash recovery
	 */
	private void saveState(String stage, int progress) throws IOException {
		Path stateFile = MCPPaths.get(mcp, EXPORT_STATE_FILE);
		Properties props = new Properties();
		props.setProperty("stage", stage);
		props.setProperty("progress", String.valueOf(progress));
		props.setProperty("side", side.name);
		props.setProperty("timestamp", LocalDateTime.now().toString());

		try (java.io.OutputStream out = Files.newOutputStream(stateFile)) {
			props.store(out, "Export state - DO NOT DELETE (used for crash recovery)");
		}
	}

	/**
	 * Attempt to recover from a previous incomplete export
	 */
	private void recoverFromState(Path stateFile) throws IOException {
		Properties props = new Properties();
		try (java.io.InputStream in = Files.newInputStream(stateFile)) {
			props.load(in);
		}

		String stage = props.getProperty("stage", "PREPARE");
		String timestamp = props.getProperty("timestamp", "unknown");

		log("Previous export was interrupted at stage: " + stage);
		log("Timestamp: " + timestamp);

		// Check for backups
		Path backupDir = MCPPaths.get(mcp, EXPORT_BACKUP_DIR);
		if (Files.exists(backupDir)) {
			List<Path> backups = FileUtil.walkDirectory(backupDir, 
				path -> !Files.isDirectory(path) && path.toString().endsWith(".zip"));
			if (!backups.isEmpty()) {
				log("Found " + backups.size() + " backup(s) available for recovery");
			}
		}
	}

	/**
	 * Create a backup of current state
	 */
	private void createBackup(String backupName) throws IOException {
		Path backupDir = MCPPaths.get(mcp, EXPORT_BACKUP_DIR);
		Path bin = MCPPaths.get(mcp, BIN, side);

		if (Files.exists(bin)) {
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
			Path backupFile = backupDir.resolve(String.format("%s_%s_%s.zip", backupName, side.name, timestamp));

			FileUtil.compress(bin, backupFile);
			log("Backup created: " + backupFile.getFileName());

			// Keep only last 5 backups to save space
			cleanOldBackups(backupDir, 5);
		}
	}

	/**
	 * Remove old backups, keeping only the most recent ones
	 */
	private void cleanOldBackups(Path backupDir, int keepCount) throws IOException {
		List<Path> backups = FileUtil.walkDirectory(backupDir, 
			path -> !Files.isDirectory(path) && path.toString().endsWith(".zip"));

		if (backups.size() > keepCount) {
			// Sort by modification time (oldest first)
			backups.sort((a, b) -> {
				try {
					return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
				} catch (IOException e) {
					return 0;
				}
			});

			// Delete oldest backups
			int toDelete = backups.size() - keepCount;
			for (int i = 0; i < toDelete; i++) {
				Files.deleteIfExists(backups.get(i));
			}
		}
	}

	@Override
	public void setProgress(int progress) {
		switch (step) {
			case STAGE_RECOMPILE: {
				int percent = (int) (progress * 0.29D);
				super.setProgress(10 + percent);
				break;
			}
			case STAGE_REOBF: {
				int percent = (int) (progress * 0.29D);
				super.setProgress(40 + percent);
				break;
			}
			case STAGE_PACKAGE: {
				int percent = (int) (progress * 0.19D);
				super.setProgress(70 + percent);
				break;
			}
			default:
				super.setProgress(progress);
				break;
		}
	}
}
