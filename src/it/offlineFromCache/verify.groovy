def online = new File(basedir, "target/online.jar")
def offline = new File(basedir, "target/offline.jar")
assert online.exists() : "Online download is missing"
assert offline.exists() : "Offline copy from cache is missing"
assert offline.text == online.text : "Offline copy differs from the online download"
assert !new File(basedir, "target/missing.jar").exists()
assert new File(basedir, "build.log").text =~ /No file in cache and maven is in offline mode/
