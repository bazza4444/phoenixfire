#!/bin/bash
# ============================================================
# Phoenix Fire — Keystore Generator
# Run this ONCE on any PC/Mac/Linux to create your signing key
# You need Java installed (just type: java -version to check)
# ============================================================

echo "🔥 Phoenix Fire Keystore Generator"
echo "==================================="
echo ""
echo "This creates your APK signing key."
echo "Keep the output values PRIVATE — they go into GitHub Secrets."
echo ""

# Prompt for details
read -p "Your name or organisation: " DNAME
read -p "City: " CITY
read -p "Country code (e.g. GB, US): " COUNTRY

# Fixed values for Phoenix Fire
KEYSTORE_FILE="phoenixfire-release.keystore"
KEY_ALIAS="phoenixfire"
STOREPASS="PhoenixFire2024!"
KEYPASS="PhoenixFire2024!"

echo ""
echo "Generating keystore..."

keytool -genkeypair \
  -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$KEY_ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$STOREPASS" \
  -keypass "$KEYPASS" \
  -dname "CN=$DNAME, OU=PhoenixFire, O=PhoenixFire, L=$CITY, ST=$CITY, C=$COUNTRY" \
  2>/dev/null

if [ $? -eq 0 ]; then
  echo ""
  echo "✅ Keystore created: $KEYSTORE_FILE"
  echo ""
  echo "======================================================"
  echo "COPY THESE 4 VALUES INTO GITHUB SECRETS (see guide):"
  echo "======================================================"
  echo ""
  echo "Secret name:  SIGNING_KEY"
  echo "Secret value: $(base64 -w 0 $KEYSTORE_FILE)"
  echo ""
  echo "Secret name:  KEY_ALIAS"
  echo "Secret value: $KEY_ALIAS"
  echo ""
  echo "Secret name:  KEY_STORE_PASSWORD"
  echo "Secret value: $STOREPASS"
  echo ""
  echo "Secret name:  KEY_PASSWORD"
  echo "Secret value: $KEYPASS"
  echo ""
  echo "======================================================"
  echo "Keep $KEYSTORE_FILE safe as a backup!"
  echo "======================================================"
else
  echo "❌ Failed — make sure Java is installed: java -version"
fi
