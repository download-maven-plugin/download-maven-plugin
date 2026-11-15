assert !new File(basedir, "target/output.jar").exists() : "File must not be downloaded when skipped"
assert new File(basedir, "build.log").text =~ /download-maven-plugin:wget skipped/
