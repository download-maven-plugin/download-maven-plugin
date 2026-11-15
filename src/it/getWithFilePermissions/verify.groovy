def file = new File(basedir, "target/tool.sh")
assert file.exists() : "File does not exist"
assert file.canExecute() : "File is not executable"
