echo "-- Compiling objective spec"
python3 compile_objective_spec.py
echo "-- Compiling flagspec"
python3 compile_flagspec.py
echo "-- Transpiling flagset and logic cores"
python3 -m metapensiero.pj flagsetcore.py -o server/srcdata/flagsetcore.js
cd server
echo "-- Compiling UI spec"
cp ../objective_data.py .
python3 compile_uispec.py
echo "-- Assembling JS all-in-one"
cat srcdata/flagspec.js srcdata/flagsetcore.js srcdata/flagsetcorelib.js > script/flags.js
echo "-- Copying to versioned filename"
cd ..
cp server/script/flags.js server/script/flags-`python3 -c "import version; import sys; sys.stdout.write('.'.join([str(i) for i in version.VERSION]))"`.js
