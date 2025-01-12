set -e
echo Making jar
gradle jar --no-daemon
echo Copying
cp ./build/libs/* ../mserv/config/mods/
