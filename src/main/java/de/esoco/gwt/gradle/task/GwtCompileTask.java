/**
 * This file is part of gwt-gradle-plugin.
 *
 * gwt-gradle-plugin is free software: you can redistribute it and/or modify it under the terms of the GNU Lesser
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * gwt-gradle-plugin is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
 * implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with gwt-gradle-plugin. If not,
 * see <http://www.gnu.org/licenses/>.
 */
package de.esoco.gwt.gradle.task;

import de.esoco.gwt.gradle.action.JavaAction;
import de.esoco.gwt.gradle.extension.CompilerOption;
import de.esoco.gwt.gradle.extension.GwtExtension;
import de.esoco.gwt.gradle.helper.CompileCommandBuilder;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.List;
import java.util.concurrent.Callable;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.ConventionMapping;
import org.gradle.api.internal.IConventionAware;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.*;

import com.google.common.base.Strings;

@CacheableTask
public class GwtCompileTask extends AbstractTask {

	public static final String NAME = "gwtCompile";

	private List<String> modules;
	private File war;
	private FileCollection src;

	public GwtCompileTask() {
		setDescription("Compile the GWT modules");

		dependsOn(JavaPlugin.COMPILE_JAVA_TASK_NAME, JavaPlugin.PROCESS_RESOURCES_TASK_NAME);
	}

	@TaskAction
	public void exec() {

		GwtExtension extension = getProject().getExtensions().getByType(GwtExtension.class);
		CompilerOption compilerOptions = extension.getCompile();
		if (!Strings.isNullOrEmpty(extension.getSourceLevel()) &&
			Strings.isNullOrEmpty(compilerOptions.getSourceLevel())) {
			compilerOptions.setSourceLevel(extension.getSourceLevel());
		}

		CompileCommandBuilder commandBuilder = new CompileCommandBuilder();
		commandBuilder.configure(getProject(), compilerOptions, getSrc(), getWar(), getModules());
		JavaAction compileAction = commandBuilder.buildJavaAction();
		compileAction.execute(this);
		compileAction.join();
		if (compileAction.exitValue() != 0) {
			throw new RuntimeException("Fail to compile GWT modules");
		}

		getProject().getTasks().getByName(GwtCheckTask.NAME).setEnabled(false);
	}

	public void configure(final Project project, final GwtExtension extension) {
		final CompilerOption options = extension.getCompile();
		options.init(project);
		options.setLocalWorkers(evalWorkers(options));

		final ConfigurableFileCollection sources = project.files();
			addSourceSet(sources, project, SourceSet.MAIN_SOURCE_SET_NAME);

		final Configuration compileClasspath = project.getConfigurations().getByName(
			JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);
		compileClasspath.getAllDependencies().withType(ProjectDependency.class, new Action<ProjectDependency>() {
			@Override
			public void execute(ProjectDependency dep) {
				addSourceSet(sources, dep.getDependencyProject(), SourceSet.MAIN_SOURCE_SET_NAME);
			}
		});

		ConventionMapping mapping = ((IConventionAware) this).getConventionMapping();

		mapping.map("modules", new Callable<List<String>>() {
			@Override
			public List<String> call()  {
				return extension.getModule();
			}
		});
		mapping.map("war", new Callable<File>() {
			@Override
			public File call()  {
				return options.getWar();
			}
		});
		mapping.map("src", new Callable<FileCollection>() {
			@Override
			public FileCollection call()  {
				return sources;
			}
		});
	}

	private void addSourceSet(ConfigurableFileCollection sources, Project project, String sourceSet) {
		JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
		
		if (javaConvention != null) {
			SourceSet mainSourceSet = javaConvention.getSourceSets().getByName(sourceSet);
			sources
				.from(project.files(mainSourceSet.getOutput().getResourcesDir()))
				.from(project.files(mainSourceSet.getOutput().getClassesDirs()))
				.from(project.files(mainSourceSet.getAllSource().getSrcDirs()));
		}
	}

	private int evalWorkers(CompilerOption options) {
		long workers = Runtime.getRuntime().availableProcessors();
		OperatingSystemMXBean osMBean = ManagementFactory.getOperatingSystemMXBean();
		if (osMBean instanceof com.sun.management.OperatingSystemMXBean) {
			com.sun.management.OperatingSystemMXBean sunOsMBean = (com.sun.management.OperatingSystemMXBean) osMBean;
			long memPerWorker = 1024L * 1024L * options.getLocalWorkersMem();
			long nbFreeMemInGb = sunOsMBean.getFreePhysicalMemorySize() / memPerWorker;

			if (nbFreeMemInGb < workers) {
				workers = nbFreeMemInGb;
			}
			if (workers < 1) {
				workers = 1;
			}
		}
		return (int) workers;
	}

	@OutputDirectory
	public File getWar() {
		return war;
	}

	@Input
	public List<String> getModules() {
		return modules;
	}

	@InputFiles
	@PathSensitive(PathSensitivity.RELATIVE)
	public FileCollection getSrc() {
		return src;
	}
}
