'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
module.exports = function loadCore() {
  const cache = new Map();
  function load(file) {
    if (cache.has(file)) return cache.get(file);
    const exported = {}; cache.set(file, exported);
    const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
    vm.runInNewContext(compiled, { exports: exported, module: { exports: exported }, Intl, Date, Error, require: name => {
      if (!name.startsWith('.')) throw new Error(`Unexpected localization dependency ${name}`);
      return load(path.resolve(path.dirname(file), `${name}.ts`));
    } }, { filename: file });
    return exported;
  }
  return { core: load(path.join(__dirname, '../core.ts')), catalog: load(path.join(__dirname, '../catalog.ts')) };
};
