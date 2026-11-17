// Seeds the cache with an outdated copy of the URL, as if it had been downloaded
// before the file changed on the server.
def url = (new File(basedir, "pom.xml").text =~ /<it\.url>([^<]+)<\/it\.url>/)[0][1]
assert url.startsWith("http") : "pom.xml was not filtered: " + url
def cache = new File(basedir, "cache")
cache.mkdirs()
new File(cache, "outdated.jar").text = "outdated"
new File(cache, "outdated.jar.meta").text = 'etag="outdated"\nsize=8\n'
def index = new HashMap<URI, String>()
index.put(new URI(url), "outdated.jar")
new File(cache, "index.ser").withObjectOutputStream { it.writeObject(index) }
