def target = new File(basedir, "target")
def cached = new File(basedir, "cache/outdated.jar")

// 1. never
assert new File(target, "never.jar").text == "outdated" : "updatePolicy=never must use the cached copy"

// 2. always: the current version is downloaded and replaces the cached copy
def current = new File(target, "always.jar").bytes
assert new String(current, "ISO-8859-1") != "outdated" : "updatePolicy=always must download the current version"
assert cached.bytes == current : "The cached copy must be replaced by the current version"

// 3. and 4.: the current version is used
assert new File(target, "again.jar").bytes == current
assert new File(target, "offline.jar").bytes == current

def log = new File(basedir, "build.log").text
assert log.contains("Cached file was outdated: downloaded the current version")
// mrm ignores If-Modified-Since and sends the file again; it is recognized as unchanged
assert log =~ /Cached file is up to date/
assert !log.contains("for a newer version, using the cached file")
