# Requires a local Gradle install (`GRADLE ?= gradle`); run `gradle wrapper` once to generate
# ./gradlew instead, then override with `make GRADLE=./gradlew ...` or `export GRADLE=./gradlew`.
GRADLE ?= gradle

# Build the plugin
assemble:
	$(GRADLE) assemble

clean:
	rm -rf .nextflow*
	rm -rf work
	rm -rf build
	$(GRADLE) clean

# Run plugin unit tests
test:
	$(GRADLE) test

# Install the plugin into the local nextflow plugins dir (~/.nextflow/plugins)
install:
	$(GRADLE) install

# Publish the plugin
release:
	$(GRADLE) releasePlugin
