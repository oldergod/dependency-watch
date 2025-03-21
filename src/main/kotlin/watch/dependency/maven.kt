package watch.dependency

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind.STRING
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import watch.dependency.MavenRepository.Versions

@Serializable(with = MavenCoordinateSerializer::class)
data class MavenCoordinate(
	val groupId: String,
	val artifactId: String,
) {
	companion object {
		fun parse(string: String): MavenCoordinate {
			val (coordinate, version) = parseCoordinates(string)
			check(version == null) {
				"Coordinate version must not be specified: '$string'"
			}
			return coordinate
		}
	}
}

private object MavenCoordinateSerializer : KSerializer<MavenCoordinate> {
	override val descriptor: SerialDescriptor =
		PrimitiveSerialDescriptor("MavenCoordinateSerializer", STRING)

	override fun deserialize(decoder: Decoder): MavenCoordinate {
		return MavenCoordinate.parse(decoder.decodeString())
	}

	override fun serialize(encoder: Encoder, value: MavenCoordinate) {
		throw UnsupportedOperationException()
	}
}

interface MavenRepository {
	val name: String
	suspend fun versions(coordinate: MavenCoordinate): Versions?

	data class Versions(
		val latest: String,
		val all: Set<String>,
	)

	interface Factory {
		fun maven2(name: String, url: HttpUrl): MavenRepository

		class Http(private val client: OkHttpClient) : Factory {
			override fun maven2(name: String, url: HttpUrl): MavenRepository {
				return HttpMaven2Repository(client, name, url)
			}
		}
	}
}

private class HttpMaven2Repository(
	private val okhttp: OkHttpClient,
	override val name: String,
	private val url: HttpUrl,
) : MavenRepository {
	override suspend fun versions(coordinate: MavenCoordinate): Versions? {
		val (groupId, artifactId) = coordinate
		val metadataUrl = url.resolve("${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml")!!
		val call = okhttp.newCall(Request.Builder().url(metadataUrl).build())
		val body = try {
			call.await()
		} catch (e: HttpException) {
			if (e.code == 404) {
				return null
			}
			throw e
		}
		val metadata = xmlFormat.decodeFromString(ArtifactMetadata.serializer(), body)
		return Versions(
			latest = metadata.versioning.release,
			all = metadata.versioning.versions.toSet()
		)
	}

	companion object {
		val xmlFormat = XML {
			unknownChildHandler = UnknownChildHandler { _, _, _, _, _ -> emptyList() }
		}
	}

	@Serializable
	@XmlSerialName("metadata", "", "")
	private data class ArtifactMetadata(
		@XmlSerialName("versioning", "", "")
		val versioning: Versioning,
	) {
		@Serializable
		data class Versioning(
			@XmlChildrenName("release", "", "")
			val release: String,
			@XmlChildrenName("version", "", "")
			val versions: List<String>,
		)
	}
}
