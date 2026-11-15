def log = new File(basedir, "build.log").text
assert log =~ /Not same digest as expected/ : "build.log does not report the checksum mismatch"
