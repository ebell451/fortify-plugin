/*******************************************************************************
 * (c) Copyright 2020 Micro Focus or one of its affiliates.
 * 
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * https://opensource.org/licenses/MIT
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.fortify.plugin.jenkins.steps;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import com.fortify.plugin.jenkins.FindExecutableRemoteService;
import com.fortify.plugin.jenkins.FortifyPlugin;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.StreamBuildListener;
import hudson.model.TaskListener;
import jenkins.tasks.SimpleBuildStep;

public abstract class FortifyStep extends Step implements SimpleBuildStep {
	public static final String VERSION = FortifyPlugin.getPluginVersion();

	protected Run<?, ?> lastBuild;

	protected void setLastBuild(Run<?, ?> lastBuild) {
		this.lastBuild = lastBuild;
	}

	/**
	 * Search for the executable filename in executable home directory or on PATH environment
	 * variable or in workspace
	 *
	 * @return found executable
	 */
	protected String getExecutable(String filename, Run<?, ?> build, FilePath workspace,
			TaskListener listener, String targetEnvVarName, EnvVars env) throws InterruptedException, IOException {
		PrintStream logger = listener.getLogger();
		String home = null;
		String path = null;
		boolean isEnvVarSetProperly = true;
		if (targetEnvVarName != null && !targetEnvVarName.equalsIgnoreCase("PATH")) {
			home = env.get(targetEnvVarName);
			if (home != null) {
				home = home.trim();
				if (endsWithBin(home)) {
					logger.println("WARNING: Environment variable " + targetEnvVarName + " should not point to bin directory");
					isEnvVarSetProperly = false;
				}
			}
		}
		for (Map.Entry<String, String> entry : env.entrySet()) {
			String envVarName = entry.getKey();
			if ("PATH".equalsIgnoreCase(targetEnvVarName)) {
				path = env.get(envVarName);
			}
		}
		return findExecutablePath(filename, home, path, workspace, logger, targetEnvVarName, isEnvVarSetProperly);
	}

	private static boolean endsWithBin(String str) {
		return str.endsWith("bin") || str.endsWith("bin/") || str.endsWith("bin\\");
	}

	private String findExecutablePath(String filename, String home, String path, FilePath workspace, PrintStream logger, String targetEnvVarName, boolean isEnvVarSetProperly)
			throws IOException, InterruptedException {
		String executablePath = workspace.act(new FindExecutableRemoteService(filename, home, path, workspace));
		if (executablePath == null) {
			throw new FileNotFoundException("ERROR: executable not found: " + filename + "; " + composeEnvVarErrorMessage(filename, targetEnvVarName, isEnvVarSetProperly));
		} else {
			logger.printf("Found executable: %s%n", executablePath);
			return executablePath;
		}
	}

	private String composeEnvVarErrorMessage(String filename, String targetEnvVarName, boolean isEnvVarSetProperly) {
		StringBuilder errorMsg = new StringBuilder();
		errorMsg.append("make sure that either ");
		if (targetEnvVarName != null) {
			errorMsg.append(targetEnvVarName).append(" environment variable is set");
			if (!isEnvVarSetProperly) {
				errorMsg.append(" properly");
			}
			errorMsg.append(" or ");
		}
		errorMsg.append(filename).append(" is on the PATH or in workspace");
		return errorMsg.toString();
	}

	protected String resolve(String param, TaskListener listener) {
		if (param == null) {
			return "";
		}
		if (lastBuild == null) {
			return param;
		}
		try {
			// if we can parse it as an Integer, then there's no need to resolve the variable
			Integer.parseInt(param);
		} catch (NumberFormatException e1) {
			listener = listener == null ? new StreamBuildListener(System.out, Charset.defaultCharset()) : listener;
			try {
				// TODO: see at lastBuild.getBuildVariableResolver()
				final EnvVars vars = lastBuild.getEnvironment(listener);
				return vars.expand(param);
			} catch (IOException e2) {
				// do nothing
			} catch (InterruptedException e3) {
				// do nothing
			}
		}
		return param;
	}

	@Override
	public boolean prebuild(AbstractBuild<?, ?> build, BuildListener listener) {
		return false;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException { //XXX
		if (build != null && launcher != null && listener != null && build.getWorkspace() != null) {
			perform(build, build.getWorkspace(), build.getEnvironment(listener), launcher, listener);
		}
		return true;
	}

	@Override
	public Action getProjectAction(AbstractProject<?, ?> project) {
		return null;
	}

	@Override
	public Collection<? extends Action> getProjectActions(AbstractProject<?, ?> project) {
		return Collections.emptyList();
	}

	@Override
	public StepExecution start(StepContext arg0) throws Exception {
		return null;
	}

	// breaks down argsToAdd into individual arguments before adding to args List.
	protected void addAllArguments(List<String> args, String argsToAdd) {
		for (String s : Util.tokenize(argsToAdd)) {
			args.add(s);
		}
	}

	// breaks down argsToAdd into individual arguments before adding to args List.
	// Adds a flag argument before each individual argument.
	protected void addAllArguments(List<String> args, String argsToAdd, String flag) {
		for (String s : Util.tokenize(argsToAdd)) {
			args.add(flag);
			args.add(s);
		}
	}

	protected void addAllArgumentsWithNoMasks(List<Pair<String, Boolean>> args, String argsToAdd, String flag) {
		for (String s : Util.tokenize(argsToAdd)) {
			args.add(Pair.of(flag, Boolean.FALSE));
			args.add(Pair.of(s, Boolean.FALSE));
		}
	}
}
