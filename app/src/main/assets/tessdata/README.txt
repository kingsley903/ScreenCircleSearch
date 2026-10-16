# Tesseract language data goes here.
#
# This directory MUST contain eng.traineddata before the app can recognise any
# text. It is not checked into git because the model is ~23 MB.
#
# 1. Download it:
#      https://github.com/tesseract-ocr/tessdata/blob/main/eng.traineddata
#      (raw file, ~23 MB - best accuracy)
#      or the smaller, faster one:
#      https://github.com/tesseract-ocr/tessdata_fast/blob/main/eng.traineddata
#      (~4 MB)
# 2. Rename it to eng.traineddata and drop it in this directory.
# 3. Verify with:  ./gradlew :app:checkTessData
#
# OCRManager.ensureTessDataExtracted() copies the file from here into the app's
# private storage (filesDir/tessdata) on first use, because libtesseract opens
# the model with plain file I/O and cannot read an AssetManager.
#
# To add another language, drop e.g. deu.traineddata in here and change
# OCRManager.LANGUAGE.