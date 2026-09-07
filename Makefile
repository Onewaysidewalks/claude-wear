.PHONY: all gateway android spike apks fixtures clean

GRADLE = cd android && ./gradlew --no-daemon -q

all: gateway android

gateway:
	cd gateway && . .venv/bin/activate && ruff check . && ruff format --check . && python scripts/gen_fixtures.py --check && pytest -q

android:
	$(GRADLE) :shared:test :phone:testDebugUnitTest :watch:testDebugUnitTest
	$(GRADLE) :spike:lintAssistDebug :spike:lintVoiceDebug :phone:lintDebug :watch:lintDebug

apks:
	$(GRADLE) :spike:assembleDebug :phone:assembleDebug :watch:assembleDebug
	@ls -1 android/*/build/outputs/apk/*/debug/*.apk android/*/build/outputs/apk/debug/*.apk 2>/dev/null

spike:
	$(GRADLE) :spike:assembleDebug
	@echo "adb install -r android/spike/build/outputs/apk/assist/debug/spike-assist-debug.apk"
	@echo "adb install -r android/spike/build/outputs/apk/voice/debug/spike-voice-debug.apk"

fixtures:
	cd gateway && . .venv/bin/activate && python scripts/gen_fixtures.py

clean:
	rm -rf android/*/build android/.gradle gateway/.pytest_cache gateway/.ruff_cache
