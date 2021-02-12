/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.*
import com.intellij.execution.target.local.LocalTargetEnvironmentFactory
import com.intellij.openapi.application.Experiments
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.util.io.BaseOutputReader
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.runconfig.buildtool.CargoPatch
import org.rust.cargo.runconfig.buildtool.cargoPatches
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.command.workingDirectory
import org.rust.cargo.runconfig.target.RsLanguageRuntimeConfiguration
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RsToolchain
import org.rust.cargo.toolchain.impl.RustcVersion
import org.rust.cargo.toolchain.tools.Cargo
import org.rust.cargo.toolchain.tools.cargoOrWrapper
import org.rust.cargo.toolchain.tools.rustc
import org.rust.stdext.toPath
import java.nio.charset.StandardCharsets
import java.nio.file.Path

@Suppress("UnstableApiUsage")
abstract class CargoRunStateBase(
    environment: ExecutionEnvironment,
    val runConfiguration: CargoCommandConfiguration,
    val config: CargoCommandConfiguration.CleanConfiguration.Ok
) : CommandLineState(environment) {
    val toolchain: RsToolchain = config.toolchain
    val commandLine: CargoCommandLine = config.cmd
    val cargoProject: CargoProject? = CargoCommandConfiguration.findCargoProject(
        environment.project,
        commandLine.additionalArguments,
        commandLine.workingDirectory
    )
    private val workingDirectory: Path? get() = cargoProject?.workingDirectory

    protected val commandLinePatches: MutableList<CargoPatch> = mutableListOf()

    init {
        commandLinePatches.addAll(environment.cargoPatches)
    }

    fun cargo(): Cargo = toolchain.cargoOrWrapper(workingDirectory)

    fun rustVersion(): RustcVersion? = toolchain.rustc().queryVersion(workingDirectory)

    fun prepareCommandLine(vararg additionalPatches: CargoPatch): CargoCommandLine {
        var commandLine = commandLine
        for (patch in commandLinePatches) {
            commandLine = patch(commandLine)
        }
        for (patch in additionalPatches) {
            commandLine = patch(commandLine)
        }
        return commandLine
    }

    override fun startProcess(): ProcessHandler = startProcess(processColors = true)

    /**
     * @param processColors if true, process ANSI escape sequences, otherwise keep escape codes in the output
     */
    fun startProcess(processColors: Boolean): ProcessHandler {
        val environmentFactory = getTargetEnvironmentFactory()
        val request = environmentFactory.createRequest()
        val projectRootOnTarget = environmentFactory.targetConfiguration?.projectRootOnTarget?.toPath()
        val commandLine = cargo().toColoredCommandLine(environment.project, prepareCommandLine())
            .makeTargeted(request, projectRootOnTarget)

        val environment = environmentFactory.prepareRemoteEnvironment(request, TargetEnvironmentAwareRunProfileState.TargetProgressIndicator.EMPTY)
        val process = environment.createProcess(commandLine, EmptyProgressIndicator())
        val commandRepresentation = commandLine.getCommandPresentation(environment)

        // TODO
        val handler = if (processColors) {
            object : KillableProcessHandler(process, commandRepresentation, commandLine.charset),
                     AnsiEscapeDecoder.ColoredTextAcceptor {
                private val decoder: AnsiEscapeDecoder = RsAnsiEscapeDecoder()

                override fun notifyTextAvailable(text: String, outputType: Key<*>) {
                    decoder.escapeText(text, outputType, this)
                }

                override fun coloredTextAvailable(text: String, attributes: Key<*>) {
                    super.notifyTextAvailable(text, attributes)
                }

                override fun readerOptions(): BaseOutputReader.Options {
                    return BaseOutputReader.Options.forMostlySilentProcess()
                }
            }
        } else {
            object : KillableProcessHandler(process, commandRepresentation, commandLine.charset) {
                override fun readerOptions(): BaseOutputReader.Options {
                    return BaseOutputReader.Options.forMostlySilentProcess()
                }
            }
        }
        handler.setShouldKillProcessSoftlyWithWinP(true)

        ProcessTerminatedListener.attach(handler) // shows exit code upon termination
        return handler
    }

    private fun GeneralCommandLine.makeTargeted(
        request: TargetEnvironmentRequest,
        targetRootPath: Path?
    ): TargetedCommandLine {
        val commandLineBuilder = TargetedCommandLineBuilder(request)
        val runtime = getTargetLanguageRuntime()

        // TODO: support `emulateTerminal` option
        // TODO: support "redirect input"
        // TODO: take into account target platform

        commandLineBuilder.setCharset(StandardCharsets.UTF_8)
        commandLineBuilder.setExePath(runtime?.cargoPath ?: "cargo")

        if (workDirectory != null) {
            val upload = request.createTempVolume().createUpload(workDirectory.toString())
            commandLineBuilder.setWorkingDirectory(upload)
        }

//        val localRootPath = workingDirectory
//        if (localRootPath != null && projectRootOnTarget != null) {
//            val targetRootString = projectRootOnTarget.toString()
//            val targetRootPath = TargetEnvironment.TargetPath.Persistent(targetRootString)
//            request.uploadVolumes += TargetEnvironment.UploadRoot(localRootPath, targetRootPath)
//            commandLineBuilder.setWorkingDirectory(targetRootString)
//        }

        for (parameter in parametersList.parameters) {
            commandLineBuilder.addParameter(parameter)
        }

        for ((key, value) in environment.entries) {
            if (key == "RUSTC") continue // TODO: fix
            commandLineBuilder.addEnvironmentVariable(key, value)
        }

        return commandLineBuilder.build()
    }

    private fun getTargetEnvironmentFactory(): TargetEnvironmentFactory {
        if (!Experiments.getInstance().isFeatureEnabled("run.targets")) return LocalTargetEnvironmentFactory()
        val targetName = runConfiguration.defaultTargetName ?: return LocalTargetEnvironmentFactory()
        val config = TargetEnvironmentsManager.getInstance(environment.project).targets.findByName(targetName)
            ?: throw ExecutionException("Cannot find target $targetName")
        return config.createEnvironmentFactory(environment.project)
    }

    private fun getTargetLanguageRuntime(): RsLanguageRuntimeConfiguration? {
        return getTargetEnvironmentFactory().targetConfiguration?.runtimes?.findByType()
    }
}
