./gradlew --console=plain compileTestJava 2>&1 | tee build/reports/errorprone/compileTestJava.txt


# Get the current directory for absolute paths
CURRENT_DIR=$(pwd)
REPORT_PATH="$CURRENT_DIR/build/reports/problems/problems-report.html"
OUTPUT_PATH="$CURRENT_DIR/build/reports/errorprone/compileTestJava.txt"

# Print the results
echo "NullAway check completed!"
echo ""
echo "📄 Raw output: file://$OUTPUT_PATH"
echo "📊 HTML report: file://$REPORT_PATH"
echo ""

# Show summary using the correct file
if grep -q "BUILD SUCCESSFUL" "$OUTPUT_PATH"; then
    echo "✅ Build successful - no null safety issues!"
else
    echo "❌ Build failed - null safety issues found"
    echo ""
    echo "Quick summary:"
    grep -E "(error:|warning:)" "$OUTPUT_PATH" | head -5

    # Fix the count comparison
    ERROR_COUNT=$(grep -c -E "(error:|warning:)" "$OUTPUT_PATH" || echo "0")
    if [ "$ERROR_COUNT" -gt 5 ]; then
        echo "... and $(( ERROR_COUNT - 5 )) more issues"
    fi
fi