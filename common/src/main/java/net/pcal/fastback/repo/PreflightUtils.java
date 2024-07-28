/*
 * FastBack - Fast, incremental Minecraft backups powered by Git.
 * Copyright (C) 2022 pcal.net
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; If not, see <http://www.gnu.org/licenses/>.
 */

package net.pcal.fastback.repo;

import net.pcal.fastback.config.GitConfig;
import net.pcal.fastback.logging.SystemLogger;
import net.pcal.fastback.utils.ProcessException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

import static net.pcal.fastback.config.FastbackConfigKey.IS_NATIVE_GIT_ENABLED;
import static net.pcal.fastback.config.FastbackConfigKey.UPDATE_GITATTRIBUTES_ENABLED;
import static net.pcal.fastback.config.FastbackConfigKey.UPDATE_GITIGNORE_ENABLED;
import static net.pcal.fastback.config.FastbackConfigKey.AUTO_GITLFS_INSTALL;
import static net.pcal.fastback.logging.SystemLogger.syslog;
import static net.pcal.fastback.utils.FileUtils.writeResourceToFile;
import static net.pcal.fastback.utils.ProcessUtils.doExec;

/**
 * Utilities for keeping the repo configuration up-to-date.
 *
 * @author pcal
 * @since 0.13.0
 */
abstract class PreflightUtils {

    // ======================================================================
    // Util methods

    /**
     * Should be called prior to any heavy-lifting with git (e.g. committing or pushing).  Ensures that
     * key settings are all set correctly.
     */
    static void doPreflight(RepoImpl repo) throws IOException, ProcessException, GitAPIException {
        final SystemLogger syslog = syslog();
        syslog.debug("Doing world maintenance");
        final Git jgit = repo.getJGit();
        final Path worldSaveDir = jgit.getRepository().getWorkTree().toPath();
        WorldIdUtils.ensureWorldHasId(worldSaveDir);
        final GitConfig config = GitConfig.load(jgit);
        if (config.getBoolean(UPDATE_GITIGNORE_ENABLED)) {
            final Path targetPath = worldSaveDir.resolve(".gitignore");
            writeResourceToFile("world/gitignore", targetPath);
        }
        if (config.getBoolean(UPDATE_GITATTRIBUTES_ENABLED)) {
            final Path targetPath = worldSaveDir.resolve(".gitattributes");
            if (config.getBoolean(IS_NATIVE_GIT_ENABLED)) {
                writeResourceToFile("world/gitattributes-native", targetPath);
            } else {
                writeResourceToFile("world/gitattributes-jgit", targetPath);
            }
        }
        if (config.getBoolean(AUTO_GITLFS_INSTALL))
            updateNativeLfsInstallation(repo);
    }

    // ======================================================================
    // Private

    /**
     * Ensures that git-lfs is installed or uninstalled in the worktree as appropriate.
     * @throws IOException 
     */
    private static void updateNativeLfsInstallation(final RepoImpl repo) throws ProcessException, GitAPIException, IOException {
        if (repo.getConfig().getBoolean(IS_NATIVE_GIT_ENABLED)) {
        	nativeGitLfsUpdater(repo);
        } else {
            try {
                // jgit has builtin support for lfs, but it's weird not compatible with native lfs, so lets just
                // try to avoid letting them use it.
                StoredConfig jgitConfig = repo.getJGit().getRepository().getConfig();
                jgitConfig.unsetSection("lfs", null);
                jgitConfig.unsetSection("filter", "lfs");
                jgitConfig.save();
            } catch (Exception ohwell) {
                syslog().debug(ohwell);
            }
        }
    }

    /**
     * <p>The native git-lfs updater called every preflight
     * (<b>WHICH CAN SKIP THE GIT-LFS INSTALL</b>)
     * </p>
     * @author Helcostr
     * @param repo The FastBack repository
     * @see net.pcal.fastback.repo.PreflightUtils.nativeGitLfsHookCheck(File)
     * @since 0.19.1
     * @throws IOException
     * @throws ProcessException
     */
    private static void nativeGitLfsUpdater(final RepoImpl repo) throws IOException, ProcessException {
    	if (!nativeGitLfsHookCheck(repo.getDirectory())) {
    		final String[] cmd = {"git", "-C", repo.getWorkTree().getAbsolutePath(), "lfs", "install", "--local"};
        	doExec(cmd, Collections.emptyMap(), s -> {}, s -> {});
    	}
    }
    /**
     * <p>Boolean indicating that we can skip GitLFS install
     * based on checking the hook file
     * </p>
     * @author Helcostr
     * @param dir of git metadata (org.eclipse.jgit.lib.Repository.getDirectory())
     * @see net.pcal.fastback.repo.PreflightUtils.nativeGitLfsUpdater(RepoImpl)
     * @since 0.19.1
     * @return Valid GitLFS
     * @throws IOException
     */
    private static boolean nativeGitLfsHookCheck(File dir) throws IOException {
    	File hooksDir = new File(dir, "hooks");
    	String[] hookNames = {"pre-push", "post-checkout", "post-commit", "post-merge"};
    	for (String hookName : hookNames) {
            File hookFile = new File(hooksDir, hookName);

            if (!hookFile.exists()) return false;
            try (BufferedReader reader = new BufferedReader(new FileReader(hookFile))) {
				String line;
				boolean isGood = false;
				while ((line = reader.readLine()) != null) {
				    if (line.equalsIgnoreCase("git lfs "
				    		+ hookName + " \"$@\"")) {
				    	isGood = true;
				    	break;
				    }
				}
				if (!isGood) return false;
			} catch (FileNotFoundException e) {
				return false;
			}
        }
    	return true;
    }
}
