# Gradle wrapper distribution verification

The wrapper uses the existing Gradle **8.14 binary-only** distribution. Its
`distributionSha256Sum` is
`61ad310d3c7d3e5da131b76bbf22b5a4c0786e9d892dae8c1658d4b484de3caa`.
The version, distribution URL and wrapper JAR are unchanged.

The value agrees with Gradle's [distribution checksum file](https://services.gradle.org/distributions/gradle-8.14-bin.zip.sha256),
[checksum reference](https://gradle.org/release-checksums/) and
[8.14 distribution release](https://github.com/gradle/gradle-distributions/releases/tag/v8.14.0).
It is the `-bin.zip` checksum, not the `-all.zip` or wrapper JAR checksum.

## Reproduce the archive check

From the repository root, with `curl` and `sha256sum` available:

```sh
work=$(mktemp -d)
expected=$(sed -n 's/^distributionSha256Sum=//p' gradle/wrapper/gradle-wrapper.properties)
curl --fail --location --output "$work/gradle-8.14-bin.zip" \
  https://services.gradle.org/distributions/gradle-8.14-bin.zip
printf '%s  %s\n' "$expected" "$work/gradle-8.14-bin.zip" | sha256sum --check -
```

A successful check prints `OK`. The downloaded archive remains in `$work`.

## Exercise the wrapper with a fresh cache

With the project's JDK 21 available:

```sh
fresh_home=$(mktemp -d)
GRADLE_USER_HOME="$fresh_home" ./gradlew --version
```

Expect Gradle 8.14 and exit status zero. This command bootstraps Gradle without
building the app. It requires access to the distribution download service.
The temporary cache remains in `$fresh_home`.

The wrapper checks the archive before extracting a new installation. An already
installed distribution with its `.ok` marker can be reused without rechecking
the archive; a warm-cache build does not independently exercise the new pin.
See the [wrapper verification documentation](https://docs.gradle.org/8.14/userguide/gradle_wrapper.html#sec:verification).
Changing the Gradle version or distribution type requires updating the checksum
together with the URL. Wrapper JAR validation is a separate check.
