package com.mezze.language.cli.`package`

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Apache Aether wrapper — resolve maven coordinates to on-disk jars.
 *
 * The `[maven]` handler in `mez sync` hands us a merged coord list and a
 * merged repo set; we return one jar path per artifact (the requested
 * coord plus every transitive), fetched into the Mezze-owned cache dir.
 *
 * The cache lives at `~/.mezze/cache/maven/` and is deliberately
 * ISOLATED from the user's `~/.m2/repository` — the Mezze store must not
 * be polluted by whatever ambient state their local IDE / mvn build has
 * put there, and vice versa. Aether treats it exactly like any local
 * repository: a coord already present skips the network round-trip; a
 * miss triggers a fetch from the configured remotes and lands under the
 * same maven layout (`<group>/<artifact>/<version>/…`).
 *
 * Default remote is Maven Central. Users add extra ones via
 * `[[maven-repo]]` in Mezze.toml. Version conflicts across the graph
 * follow Aether's nearest-wins policy; the caller logs a warning when
 * the same coord's version differs across packages so the drift is
 * visible in `mez sync` output.
 */
object MavenResolver {

    /** One extra remote repo declared by `[[maven-repo]]`. */
    data class MavenRemote(val id: String, val url: String)

    /** One resolved artifact — the on-disk jar plus its integrity. */
    data class ResolvedMavenArtifact(
        /** `group:artifact:version` — as it appears in the maven coord set. */
        val coord: String,
        /** Absolute path to the jar in the local cache. */
        val file: Path,
        /** sha256 hex of the jar's bytes — recorded in `Mezze.lock`. */
        val sha256: String,
    )

    /** Structured error surface — `SyncCommand` renders these verbatim. */
    class MavenResolverException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Resolve every [coords] entry (plus its transitives) into
     * [cacheDir], drawing from Maven Central + every entry of [repos].
     *
     * The returned list is deduped by coord + sorted for stable
     * `Mezze.lock` output. A single failure surfaces as a
     * [MavenResolverException] with the offending coord and the
     * underlying cause; partial success is intentionally not
     * supported — a missing artifact means the wire child would fail
     * later, and mez sync must catch that up-front.
     */
    fun resolve(
        coords: List<String>,
        cacheDir: Path,
        repos: List<MavenRemote>,
    ): List<ResolvedMavenArtifact> {
        if (coords.isEmpty()) return emptyList()

        val system = newRepositorySystem()
        val session = newSession(system, cacheDir.toFile())
        val remotes = buildRemotes(repos)

        // Flatten every requested coord's transitive closure into one
        // request. Aether de-dupes by coord internally and picks
        // nearest-wins on version conflicts across the union.
        val request = CollectRequest().apply {
            for (coord in coords) {
                val artifact = try {
                    DefaultArtifact(coord)
                } catch (e: IllegalArgumentException) {
                    throw MavenResolverException(
                        "MavenResolver: bad coord '$coord' — expected <group>:<artifact>:<version>",
                        e,
                    )
                }
                addDependency(Dependency(artifact, "runtime"))
            }
            repositories = remotes
        }

        val results = try {
            system.resolveDependencies(session, DependencyRequest(request, null))
        } catch (e: DependencyResolutionException) {
            // Aether packs the root cause into the message chain;
            // we surface it verbatim so users see WHICH jar failed and
            // WHY (404, network, checksum mismatch, …).
            val details = e.message ?: e.javaClass.simpleName
            throw MavenResolverException(
                "MavenResolver: failed to resolve ${coords.joinToString(", ")} — $details",
                e,
            )
        } catch (e: Exception) {
            throw MavenResolverException(
                "MavenResolver: unexpected error resolving ${coords.joinToString(", ")} — ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }

        // artifactResults carries one entry per artifact in the
        // resolved graph. Map to our public shape + compute sha256 on
        // the local file so the lockfile row is complete.
        val out = mutableListOf<ResolvedMavenArtifact>()
        for (ar in results.artifactResults) {
            val art = ar.artifact ?: continue
            val f = art.file ?: continue
            val jarPath = f.toPath()
            val coord = "${art.groupId}:${art.artifactId}:${art.version}"
            val sha = sha256Hex(f)
            out += ResolvedMavenArtifact(coord = coord, file = jarPath, sha256 = sha)
        }
        // Stable output — same input graph, same lock rows.
        return out.distinctBy { it.coord }.sortedBy { it.coord }
    }

    // ─── Aether wiring ────────────────────────────────────────────────

    /**
     * Aether's `RepositorySystem`, wired EXPLICITLY.
     *
     * This used to go through `MavenRepositorySystemUtils.newServiceLocator()`,
     * which instantiates each service by REFLECTION. native-image cannot do that
     * without per-class configuration, so every service came back null and the
     * locator produced no `RepositorySystem` — `mez sync` with `[maven]` deps died
     * on the installed native binary with "RepositorySystem missing from service
     * locator" while working perfectly on the JVM.
     *
     * `RepositorySystemSupplier` builds the same graph with plain constructor
     * calls, so it needs no native-image configuration at all. It is also the
     * supported replacement — the ServiceLocator API is deprecated in this line of
     * maven-resolver.
     *
     * It supplies the connector and the file/http transports itself, so the three
     * `addService` calls that used to be here are gone rather than moved: they
     * were registering what the supplier already wires. It is Maven-aware too —
     * its `getArtifactDescriptorReader` takes Maven's `ModelBuilder`, which is what
     * reads POMs, so transitive resolution is unaffected.
     */
    private fun newRepositorySystem(): RepositorySystem = RepositorySystemSupplier().get()

    private fun newSession(system: RepositorySystem, cacheDir: File): DefaultRepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val local = LocalRepository(cacheDir)
        session.localRepositoryManager = system.newLocalRepositoryManager(session, local)
        // Enable checksum verification (Maven Central serves .sha1
        // alongside every artifact; a mismatch is a hard fail — same
        // supply-chain guarantee our `[jars]` handler enforces).
        session.checksumPolicy = "fail"
        session.isOffline = false
        return session
    }

    /**
     * Merge Maven Central with the user's declared repos. Central goes
     * first so a "declared and central" coord resolves without needing
     * every user to configure the fallback themselves.
     */
    private fun buildRemotes(repos: List<MavenRemote>): List<RemoteRepository> {
        val out = mutableListOf<RemoteRepository>()
        out += RemoteRepository.Builder(
            "central", "default", "https://repo.maven.apache.org/maven2/",
        ).build()
        for (r in repos) {
            out += RemoteRepository.Builder(r.id, "default", r.url).build()
        }
        return out
    }

    // ─── sha256 ────────────────────────────────────────────────────────

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
