@file:JvmName("Main")

package watch.dependency

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
import okhttp3.logging.HttpLoggingInterceptor.Logger
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import kotlin.time.minutes

fun main(vararg args: String) {
	NoOpCliktCommand(name = "dependency-watch")
		.subcommands(
			AwaitCommand(),
			MonitorCommand(FileSystems.getDefault()),
		)
		.main(args)
}

private abstract class DependencyWatchCommand(
	name: String,
	help: String = ""
) : CliktCommand(name = name, help = help) {
	protected val debug by option(hidden = true)
		.switch<Debug>(mapOf("--debug" to Debug.Console))
		.default(Debug.Disabled)
	private val ifttt by option("--ifttt",
			help = "IFTTT webhook URL to trigger (see https://ifttt.com/maker_webhooks)",
			metavar = "URL"
		)
		.convert { it.toHttpUrl() }

	final override fun run() = runBlocking {
		val okhttp = OkHttpClient.Builder()
			.apply {
				if (debug.enabled) {
					addNetworkInterceptor(HttpLoggingInterceptor(object : Logger {
						override fun log(message: String) {
							debug.log { message }
						}
					}).setLevel(BASIC))
				}
			}
			.build()

		val mavenCentralUrl = "https://repo1.maven.org/maven2/".toHttpUrl()
		val mavenCentral = Maven2Repository(okhttp, mavenCentralUrl)

		val notifier = buildList {
			add(ConsoleNotifier)
			ifttt?.let { ifttt ->
				add(IftttNotifier(okhttp, ifttt))
			}
		}.flatten()

		try {
			execute(mavenCentral, notifier)
		} finally {
			okhttp.dispatcher.executorService.shutdown()
			okhttp.connectionPool.evictAll()
		}
	}

	protected abstract suspend fun execute(
		mavenRepository: Maven2Repository,
		notifier: Notifier,
	)
}

private class AwaitCommand : DependencyWatchCommand(
	name = "await",
	help = "Wait for an artifact to appear on Maven central then exit",
) {
	private val coordinates by argument("coordinates", help = "Maven coordinates (e.g., 'com.example:example:1.0.0')")

	override suspend fun execute(
		mavenRepository: Maven2Repository,
		notifier: Notifier,
	) {
		val (groupId, artifactId, version) = parseCoordinates(coordinates)
		checkNotNull(version) {
			"Coordinate version must be present and non-empty: '$coordinates'"
		}
		debug.log { "$groupId:$artifactId:$version" }

		while (true) {
			debug.log { "Fetching metadata for $groupId:$artifactId..."  }
			val versions = mavenRepository.versions(groupId, artifactId)
			debug.log { "$groupId:$artifactId $versions" }

			if (version in versions) {
				break
			}

			val pause = 1.minutes
			debug.log { "Sleeping $pause..." }
			delay(pause)
		}

		notifier.notify(groupId, artifactId, version)
	}
}

private class MonitorCommand(
	fs: FileSystem
) : DependencyWatchCommand(
	name = "monitor",
	help = "Constantly monitor Maven coordinates for new versions",
) {
	private val config by argument("config").path(fs)

	override suspend fun execute(
		mavenRepository: Maven2Repository,
		notifier: Notifier,
	) {
		val database = InMemoryDatabase()

		while (true) {
			val config = Config.parse(config.readText())
			debug.log { config.toString() }

			supervisorScope {
				for (coordinates in config.coordinates) {
					val (groupId, artifactId, version) = parseCoordinates(coordinates)
					check(version == null) {
						"Coordinate version must not be specified: '$coordinates'"
					}

					launch(start = UNDISPATCHED) {
						debug.log { "Fetching metadata for $groupId:$artifactId..."  }
						val versions = mavenRepository.versions(groupId, artifactId)
						debug.log { "$groupId:$artifactId $versions" }

						for (mavenVersion in versions) {
							if (!database.coordinatesSeen(groupId, artifactId, mavenVersion)) {
								database.markCoordinatesSeen(groupId, artifactId, mavenVersion)
								notifier.notify(groupId, artifactId, mavenVersion)
							}
						}
					}
				}
			}

			val pause = 1.minutes
			debug.log { "Sleeping $pause..." }
			delay(pause)
		}
	}
}

private fun parseCoordinates(coordinates: String): Triple<String, String, String?> {
	val firstColon = coordinates.indexOf(':')
	check(firstColon > 0) {
		"Coordinate ':' must be present and after non-empty groupId: '$coordinates'"
	}
	val groupId = coordinates.substring(0, firstColon)

	val secondColon = coordinates.indexOf(':', startIndex = firstColon + 1)
	if (secondColon == -1) {
		check(firstColon < coordinates.length) {
			"Coordinate artifactId must be non-empty: '$coordinates'"
		}
		return Triple(groupId, coordinates.substring(firstColon + 1), null)
	}
	check(secondColon > firstColon + 1) {
		"Coordinate artifactId must be non-empty: '$coordinates'"
	}
	val artifactId = coordinates.substring(firstColon + 1, secondColon)

	check(secondColon < coordinates.length) {
		"Coordinate version must be non-empty: '$coordinates'"
	}
	val version = coordinates.substring(secondColon + 1)

	return Triple(groupId, artifactId, version)
}
