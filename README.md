[![Maven Central](https://img.shields.io/maven-central/v/io.github.download-maven-plugin/download-maven-plugin?color=31c653&label=maven%20central)](https://central.sonatype.com/artifact/io.github.download-maven-plugin/download-maven-plugin)
[![Donate](https://img.shields.io/badge/Donate-PayPal-blue.svg)](https://www.paypal.com/donate/?business=STCX76K8KUT84&no_recurring=0&item_name=Support+download-maven-plugin+project&currency_code=USD)

[![Donate](https://raw.githubusercontent.com/vshymanskyy/StandWithUkraine/main/banner2-direct.svg)](https://stand-with-ukraine.pp.ua/)

# Download Plugin for Maven
This is a plugin meant to help maven user to download different files on different protocol in part of maven build.
The plugin caches downloaded files in maven cache directory, which saves network traffic and speedup build.

## IMPORTANT! Group ID relocation
Starting from 2.0.0 the plugin has new group ID `io.github.download-maven-plugin`

## Project Status

Functional but not under active development. We accept pull requests, and generally get them merged within a week or 2 depending on the complexity.

## Basic Usage

### "Artifact" goal
Meant to be used from anywhere on the system to download an artifact at a specific location.  Does not need a pom file to be run and can be used directly from the command line.
Can be an alternative to [maven-dependency-plugin:get](http://maven.apache.org/plugins/maven-dependency-plugin/get-mojo.html) or [maven-dependency-plugin:unpack](http://maven.apache.org/plugins/maven-dependency-plugin/unpack-mojo.html) mojoes.


```
mvn io.github.download-maven-plugin:download-maven-plugin:<LATEST_VERSION>:artifact -DgroupId=io.github.download-maven-plugin -DartifactId=download-maven-plugin -Dversion=2.0.0 -DoutputDirectory=temp
```

### "WGet" goal
This is meant to provide the necessary tooling for downloading anything in your Maven build without having to use Ant scripts.
It provides caching and checksum verification.
```xml
<plugin>
	<groupId>io.github.download-maven-plugin</groupId>
	<artifactId>download-maven-plugin</artifactId>
	<version>LATEST_VERSION</version>
	<executions>
		<execution>
			<id>install-jbpm</id>
			<phase>pre-integration-test</phase>
			<goals>
				<goal>wget</goal>
			</goals>
		</execution>
	</executions>
	<configuration>
		<url>http://downloads.sourceforge.net/project/jbpm/jBPM%203/jbpm-3.1.4/jbpm-3.1.4.zip</url>
		<unpack>true</unpack>
		<outputDirectory>${project.build.directory}/jbpm-3.1.4</outputDirectory>
		<md5>df65b5642f33676313ebe4d5b69a3fff</md5>
	</configuration>
</plugin>
```

You can also run it without a pom.xml i.e. 

`mvn -Ddownload.url=https://example.com -Ddownload.outputDirectory=. -Ddownload.outputFileName=example.html io.github.download-maven-plugin:download-maven-plugin:<LATEST_VERSION>:wget`

### Files that change behind the same URL

By default a file that is in the cache is never downloaded again. That is right for versioned URLs,
but not for URLs whose content changes, such as `.../releases/latest/download/tool.zip`.
For those, set `updatePolicy` to `always`:

```xml
<configuration>
	<url>https://github.com/example/tool/releases/latest/download/tool.zip</url>
	<updatePolicy>always</updatePolicy>
</configuration>
```

The plugin then asks the server whether the cached file is still current, with a conditional request
(`If-None-Match` / `If-Modified-Since`, from the `ETag` / `Last-Modified` the server sent with the file).
If the server answers `304 Not Modified`, nothing is downloaded. Otherwise the file is downloaded again,
and it only counts as changed if its content differs from the cached copy.

* A server that sends neither `ETag` nor `Last-Modified`, or ignores conditional requests,
  sends the whole file on every build. The same happens once for files cached by plugin versions
  before 2.2.0, which didn't record these headers.

* If the server can't be reached, answers with a server error (`5xx`) or with `429 Too Many Requests`,
  the cached file is used and a warning is logged.
* If the server refuses the request (`4xx`, e.g. the file is gone or the credentials expired),
  the build fails, unless `failOnError` is `false`: then the cached file is used and a warning is logged.
* No request is made in offline mode, or when a checksum (`md5`, `sha1`, `sha256`, `sha512`) is configured,
  because the checksum already pins the content.
* An existing output file is still left alone unless `overwrite` is set.
* `unpackWhenChanged` unpacks again only if the content has really changed.

## Requirements

Java 8 or greater

Maven
  - `3.6.3` or greater for plugin versions >= `1.11.0`
  - `3.2.5` or greater for plugin version >= `1.6.9` & < `1.11.0`

## Known issues and workarounds

### IO Error: No such archiver

Happens when the plugin is instructed to unarchive file but the file has unsupported extension

**Solution**: Specify `outputFilename` parameter with proper file extension

## Help

### Maven help

To get basic plugin help, type in the command : 
```
mvn io.github.download-maven-plugin:download-maven-plugin:help
```

To get a more detailed help, type command : 
```
mvn io.github.download-maven-plugin:download-maven-plugin:help -Ddetail
```

### Issue Tracker and wikis...

Are maintained at GitHub (links above).

### Contribute

This project support GitHub PR, but enforce some rules for decent tracking: 1 Change Request == 1 PR == 1 commit, if a change can be made by iterations, then use a specific PR for each iteration.
Ideally, every bugfix should be supplied with a unit or integration test. 

Build requirements are specified in `.tools-versions`.

