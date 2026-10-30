/**
 * Applies the app's ID, version and build number (app.config.mjs) to the native projects, and writes the offline
 * page's settings. Run before every build: `npm run configure` (part of `npm run sync`).
 */
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { appEnv, appId, buildNumber, serverUrl, version } from "../app.config.mjs";

const root = new URL("../", import.meta.url);
const build = buildNumber();

function edit(path, replacements) {
  const file = new URL(path, root);
  if (!existsSync(file)) return false;
  let text = readFileSync(file, "utf8");
  for (const [pattern, value] of replacements) {
    if (!pattern.test(text)) throw new Error(`${path}: ${pattern} not found`);
    text = text.replace(pattern, value);
  }
  writeFileSync(file, text);
  return true;
}

writeFileSync(
  new URL("www/app-config.js", root),
  `// Written by scripts/configure.mjs. The offline page's settings.\nwindow.LATESTAY = ${JSON.stringify({ serverUrl, version, appEnv })};\n`,
);

const android = edit("android/app/build.gradle", [
  [/applicationId "[^"]*"/, `applicationId "${appId}"`],
  [/versionCode \d+/, `versionCode ${build}`],
  [/versionName "[^"]*"/, `versionName "${version}"`],
]);
const ios = edit("ios/App/App.xcodeproj/project.pbxproj", [
  [/PRODUCT_BUNDLE_IDENTIFIER = [^;]*;/g, `PRODUCT_BUNDLE_IDENTIFIER = ${appId};`],
  [/MARKETING_VERSION = [^;]*;/g, `MARKETING_VERSION = ${version};`],
  [/CURRENT_PROJECT_VERSION = [^;]*;/g, `CURRENT_PROJECT_VERSION = ${build};`],
]);
console.log(`LateStay ${version} (${build}), ${appId}, ${appEnv}: ${serverUrl}` +
  `${android ? "; android" : ""}${ios ? "; ios" : ""}`);
