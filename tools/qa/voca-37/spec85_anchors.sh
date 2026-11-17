BASE=${1:-fa57a07}
n=0; fail=0
while IFS='|' read -r path line want; do
  [ -z "$path" ] && continue
  n=$((n+1))
  got=$(git show "$BASE:$path" | sed -n "${line}p")
  if printf '%s' "$got" | grep -qF -- "$want"; then
    printf 'PASS  %s:%s\n' "$path" "$line"
  else
    printf 'FAIL  %s:%s  期望含[%s]  实际[%s]\n' "$path" "$line" "$want" "$(printf '%s' "$got" | sed 's/^[[:space:]]*//')"
    fail=$((fail+1))
  fi
done <<'REFS'
app/src/main/java/com/ispeech/input/CandidateView.kt|24|不显示翻页箭头
app/src/main/java/com/ispeech/input/CandidateView.kt|67|fun update(code: String, list: List<LookupCandidate>) {
app/src/main/java/com/ispeech/input/CandidateView.kt|69|codeLabel.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE
app/src/main/java/com/ispeech/input/CandidateView.kt|76|if (list.isEmpty()) {
app/src/main/java/com/ispeech/input/CandidateView.kt|77|scrollView.visibility = View.GONE
app/src/main/java/com/ispeech/input/CandidateView.kt|79|scrollView.visibility = View.VISIBLE
app/src/main/java/com/ispeech/input/CandidateView.kt|81|scrollView.post { scrollView.scrollTo(0, 0) }
app/src/main/java/com/ispeech/input/CandidateView.kt|90|fun scrollCandidates(steps: Int) {
app/src/main/java/com/ispeech/input/CandidateView.kt|92|scrollView.smoothScrollBy(steps * dp(SCROLL_STEP_DP), 0)
app/src/main/java/com/ispeech/input/CandidateView.kt|106|setTextColor(if (highlighted) primaryColor else TEXT_PRIMARY)
app/src/main/java/com/ispeech/input/CandidateView.kt|116|val badge = TextView(context).apply {
app/src/main/java/com/ispeech/input/CandidateView.kt|117|text = (index + 1).toString()
app/src/main/java/com/ispeech/input/CandidateView.kt|141|contentDescription = candidate.display
app/src/main/java/com/ispeech/input/CandidateView.kt|139|isClickable = true
app/src/main/java/com/ispeech/input/CandidateView.kt|142|setOnClickListener { onCandidatePicked?.invoke(index) }
app/src/main/java/com/ispeech/input/InputMode.kt|5|五笔与拼音都是「中文态」
app/src/main/java/com/ispeech/input/InputMode.kt|6|enum class InputMode { WUBI, PINYIN, ENGLISH }
app/src/main/java/com/ispeech/input/CandidateView.kt|179|private fun resolvePrimaryColor(): Int {
app/src/main/java/com/ispeech/input/CandidateView.kt|199|private const val SCROLL_STEP_DP = 72
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|146|private val keyMargin = 4
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|264|addView(buildBottomPadding())
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|415|private fun buildBottomPadding(): View = View(context).apply {
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|475|contentDescription = if (effectiveKey.action == KeyAction.SHIFT) {
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|516|keyLabel(effectiveKey)
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|684|private fun installSpaceGesture(target: View, key: KeyDef) {
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|759|view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|765|private fun startRepeatDelete() {
app/src/main/java/com/ispeech/settings/store/KeyboardSettings.kt|5|COMPACT("紧凑", 46),
app/src/main/java/com/ispeech/input/keyboard/KeyboardLayouts.kt|73|KeyDef.fn(KeyAction.BACKSPACE, "⌫"
app/src/main/res/values/colors.xml|4|<color name="ispeech_primary">#5B6CFF</color>
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|963|const val TOOLBAR_HEIGHT_DP = 34
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|989|const val DOUBLE_TAP_MS = 320L
app/src/main/java/com/ispeech/input/IspeechImeService.kt|105|private const val CANDIDATE_POOL_FACTOR = 3
app/src/main/java/com/ispeech/input/IspeechImeService.kt|186|private val candidatePoolLimit: Int
app/src/main/java/com/ispeech/input/IspeechImeService.kt|187|get() = settings.candidatePageSize * CANDIDATE_POOL_FACTOR
app/src/main/java/com/ispeech/input/IspeechImeService.kt|458|KeyAction.SPACE -> handleSpace()
app/src/main/java/com/ispeech/input/IspeechImeService.kt|536|override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
app/src/main/java/com/ispeech/input/IspeechImeService.kt|604|if (keyCode == KeyEvent.KEYCODE_SPACE) handleSpace() else handleEnter()
app/src/main/java/com/ispeech/input/IspeechImeService.kt|609|if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
app/src/main/java/com/ispeech/input/IspeechImeService.kt|620|if (keyCode == KeyEvent.KEYCODE_MINUS
app/src/main/java/com/ispeech/input/IspeechImeService.kt|622|candidateView?.scrollCandidates(if (keyCode == KeyEvent.KEYCODE_MINUS) -1 else 1)
app/src/main/java/com/ispeech/input/IspeechImeService.kt|760|commitDirect(" ", CommitType.PUNCTUATION)
app/src/main/java/com/ispeech/settings/store/KeyboardSettings.kt|20|val candidatePageSize: Int = 9,
app/src/main/java/com/ispeech/settings/store/KeyboardSettings.kt|22|val chinesePunctuation: Boolean = true,
app/src/main/res/layout/candidate_view.xml|23|android:layout_height="wrap_content"
app/src/main/res/layout/candidate_view.xml|11|android:background="#F4F5F7"
app/src/main/res/layout/candidate_view.xml|34|<HorizontalScrollView
app/src/main/res/layout/candidate_view.xml|39|android:minHeight="38dp"
app/src/main/res/layout/candidate_view.xml|40|android:overScrollMode="never"
app/src/main/res/layout/candidate_view.xml|41|android:scrollbars="none"
app/src/main/java/com/ispeech/input/SoftKeyboardView.kt|957|const val DEFAULT_KEY_HEIGHT_DP = 46
app/src/main/java/com/ispeech/input/IspeechImeService.kt|735|private fun handleSpace(via: String = "soft") {
app/src/main/java/com/ispeech/settings/store/KeyboardSettings.kt|19|val keyHeightDp: Int = 46,
app/src/main/res/layout/candidate_view.xml|10|android:layout_height="46dp"
REFS
printf '== 共 %d 条，失败 %d 条 ==\n' "$n" "$fail"
