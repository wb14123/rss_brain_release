#/bin/sh

wget -c 'https://rsshub.js.org/build/radar-rules.js'
(echo "var a = " ; cat radar-rules.js ; echo "; console.log(JSON.stringify(a, null, 2));") > radar-rules-tmp.js
node radar-rules-tmp.js > ../src/main/resources/radar-rules.json
rm radar-rules.js
rm radar-rules-tmp.js
