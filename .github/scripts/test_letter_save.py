from pathlib import Path
import re

source = Path("letters/index.html").read_text(encoding="utf-8")

required = [
    'const ACTIVE_SAVED_KEY = "whiteSaffron.activeSavedDocumentId.v1";',
    'function setActiveSavedDocumentId(id)',
    'function collapseExactSavedDuplicates(list)',
    'function normalizeSavedDocuments()',
    'function applyStandardTransferWording(enableAuto=true,announce=true)',
    'function startNewTransferLetter()',
    'activeSavedDocumentId ? list.findIndex(x=>x.id===activeSavedDocumentId) : -1',
    'list.findIndex(x=>sameSavedDocumentData(x,meta.page,data))',
    'setActiveSavedDocumentId(item.id);',
    'Save Changes',
    'Edit / Open',
    'Use Standard Wording',
    'Employee Transfer Letter',
    'Document ready to edit',
    'if(typeof setActiveSavedDocumentId==="function") setActiveSavedDocumentId("");',
]
missing = [item for item in required if item not in source]
assert not missing, f"Missing implementation markers: {missing}"

old_duplicate_save = '''const item={id:`doc-${Date.now()}`,...meta,data,savedAt:new Date().toISOString()};
  const list=getSavedDocuments(); list.unshift(item); putJSON(SAVED_KEY,list);
  renderSavedDocuments(); showToast("Document saved");'''
assert old_duplicate_save not in source, "Old duplicate-save implementation is still present"

assert source.count('const ACTIVE_SAVED_KEY = "whiteSaffron.activeSavedDocumentId.v1";') == 1, "Patch was applied more than once"
assert source.count('id="newTransferLetterBtn"') == 1, "New Letter control was duplicated"
assert source.count('id="standardTransferWordingBtn"') == 1, "Standard wording control was duplicated"

scripts = re.findall(r'<script(?:\s[^>]*)?>(.*?)</script>', source, flags=re.S | re.I)
inline = "\n".join(script for script in scripts if script.strip())
assert inline.strip(), "No inline JavaScript found"
Path("/tmp/letters-inline.js").write_text(inline, encoding="utf-8")

print("saved-letter regression checks passed")
