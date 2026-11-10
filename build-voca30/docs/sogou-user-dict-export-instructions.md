# Sogou Wubi User Dictionary Export Instructions

This document provides step-by-step instructions for exporting your Sogou Wubi user dictionary from PC and importing it into iSpeech.

## Prerequisites
- A Windows or macOS computer with Sogou Input Method installed
- The same Sogou account used on your Android device
- iSpeech v1.5 or later installed on your Android device

## Step 1: Export from Sogou PC

1. Open Sogou Input Method on your PC
2. Right-click the Sogou icon in the system tray (bottom-right corner)
3. Select "设置" (Settings) → "词库设置" (Dictionary Settings)
4. Click on "用户词库" (User Dictionary)
5. Click the "导出" (Export) button
6. Choose a location to save the exported file (e.g., Desktop)
7. The exported file will be named something like `sogou_user_dict_20261101.txt`

## Step 2: Transfer to Android

1. Connect your Android device to your computer via USB
2. Copy the exported `.txt` file to your Android device's internal storage
3. Alternatively, email the file to yourself and download it on your Android device

## Step 3: Import into iSpeech

1. Open iSpeech settings on your Android device
2. Go to "词典" → "用户词库导入"
3. Select the exported `.txt` file
4. iSpeech will process and import all 15,530 words

## Important Notes
- The export process from Sogou PC will include all your user words, including those synced from your Android device
- iSpeech's import script automatically handles the conversion from Sogou's format to iSpeech's internal format
- After import, you can verify the word count in iSpeech's "词典" → "用户词库" section

For troubleshooting, refer to `tools/import/import_sogou_userdict.py` which contains the detailed conversion logic.