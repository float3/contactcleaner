package com.example.contactcleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.icu.text.Transliterator;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 7;
    private static final int MIN_PHONE_DIGITS = 7;

    private LinearLayout content;
    private EditText countryCodeInput;
    private Button rescanButton;
    private TextView summary;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final List<ContactInfo> contacts = new ArrayList<>();
    private final List<List<ContactInfo>> duplicateGroups = new ArrayList<>();
    private final List<ContactInfo> emptyContacts = new ArrayList<>();
    private final List<PhoneFix> phoneFixes = new ArrayList<>();
    private final List<DuplicatePhoneFix> duplicatePhoneFixes = new ArrayList<>();
    private final List<RomanizationFix> romanizationFixes = new ArrayList<>();
    private String currentCountryCode = "+1";
    private String currentCountryDigits = "1";

    private interface RawContactPairConsumer {
        void accept(long first, long second);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PhoneAccount.ensure(this);
        buildUi();
        if (hasContactsPermission()) {
            scanContacts();
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS}, PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && hasContactsPermission()) {
            scanContacts();
        } else {
            summary.setText("Contacts permission is needed before cleanup can run.");
        }
    }

    @Override
    protected void onDestroy() {
        scanExecutor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(28));
        content.setBackgroundColor(Color.rgb(247, 247, 242));
        scrollView.addView(content);

        content.addView(text("Contact Cleaner", 28, true));

        summary = text("Scanning contacts...", 16, false);
        summary.setPadding(0, dp(8), 0, dp(14));
        content.addView(summary);

        LinearLayout controls = row();
        countryCodeInput = new EditText(this);
        countryCodeInput.setText(defaultCountryCode());
        countryCodeInput.setSingleLine(true);
        countryCodeInput.setInputType(InputType.TYPE_CLASS_PHONE);
        countryCodeInput.setHint("+1");
        controls.addView(countryCodeInput, new LinearLayout.LayoutParams(0, dp(52), 1.0f));
        rescanButton = button("Scan");
        rescanButton.setOnClickListener(v -> scanContacts());
        controls.addView(rescanButton);
        content.addView(controls);

        setContentView(scrollView);
    }

    private void scanContacts() {
        currentCountryCode = normalizeCountryCode(countryCodeInput.getText().toString());
        currentCountryDigits = digitsOnly(currentCountryCode);
        summary.setText("Scanning contacts...");
        rescanButton.setEnabled(false);
        if (content.getChildCount() > 3) {
            content.removeViews(3, content.getChildCount() - 3);
        }
        scanExecutor.execute(() -> {
            try {
                contacts.clear();
                duplicateGroups.clear();
                emptyContacts.clear();
                phoneFixes.clear();
                duplicatePhoneFixes.clear();
                romanizationFixes.clear();
                loadContacts();
                findDuplicates();
                findEmptyContacts();
                findMissingCountryCodes();
                findDuplicatePhoneValues();
                findRomanizationFixes();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    rescanButton.setEnabled(true);
                    renderResults();
                });
            } catch (RuntimeException e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    rescanButton.setEnabled(true);
                    summary.setText("Scan failed: " + e.getMessage());
                    Toast.makeText(this, "Scan failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadContacts() {
        Map<Long, ContactInfo> byId = new LinkedHashMap<>();
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.LOOKUP_KEY},
                null, null,
                ContactsContract.Contacts.DISPLAY_NAME + " COLLATE LOCALIZED ASC")) {
            while (cursor != null && cursor.moveToNext()) {
                ContactInfo info = new ContactInfo();
                info.contactId = cursor.getLong(0);
                info.name = clean(cursor.getString(1));
                info.lookupKey = cursor.getString(2);
                byId.put(info.contactId, info);
            }
        }
        loadPhones(resolver, byId);
        loadEmails(resolver, byId);
        loadRawContactIds(resolver, byId);
        loadStructuredNames(resolver, byId);
        contacts.addAll(byId.values());
    }

    private void loadPhones(ContentResolver resolver, Map<Long, ContactInfo> byId) {
        try (Cursor cursor = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER},
                null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                ContactInfo info = byId.get(cursor.getLong(1));
                if (info != null) {
                    PhoneValue phone = new PhoneValue();
                    phone.dataId = cursor.getLong(0);
                    phone.number = clean(cursor.getString(2));
                    phone.normalized = clean(cursor.getString(3));
                    info.phones.add(phone);
                }
            }
        }
    }

    private void loadEmails(ContentResolver resolver, Map<Long, ContactInfo> byId) {
        try (Cursor cursor = resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS},
                null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                ContactInfo info = byId.get(cursor.getLong(0));
                if (info != null) {
                    info.emails.add(clean(cursor.getString(1)).toLowerCase(Locale.US));
                }
            }
        }
    }

    private void loadRawContactIds(ContentResolver resolver, Map<Long, ContactInfo> byId) {
        try (Cursor cursor = resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                new String[]{ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE},
                ContactsContract.RawContacts.DELETED + "=0", null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                ContactInfo info = byId.get(cursor.getLong(1));
                if (info != null) {
                    RawContactValue raw = new RawContactValue();
                    raw.rawContactId = cursor.getLong(0);
                    raw.accountType = clean(cursor.getString(2));
                    info.rawContacts.add(raw);
                }
            }
        }
    }

    private void loadStructuredNames(ContentResolver resolver, Map<Long, ContactInfo> byId) {
        try (Cursor cursor = resolver.query(
                ContactsContract.Data.CONTENT_URI,
                new String[]{
                        ContactsContract.Data._ID,
                        ContactsContract.Data.RAW_CONTACT_ID,
                        ContactsContract.Data.CONTACT_ID,
                        ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME,
                        ContactsContract.CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME,
                        ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME},
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE},
                null)) {
            while (cursor != null && cursor.moveToNext()) {
                ContactInfo info = byId.get(cursor.getLong(2));
                RawContactValue raw = info == null ? null : info.findRawContact(cursor.getLong(1));
                if (raw != null) {
                    raw.nameDataId = cursor.getLong(0);
                    raw.phoneticGivenName = clean(cursor.getString(3));
                    raw.phoneticMiddleName = clean(cursor.getString(4));
                    raw.phoneticFamilyName = clean(cursor.getString(5));
                }
            }
        }
    }

    private void findDuplicates() {
        UnionFind unionFind = new UnionFind(contacts.size());
        Map<String, Integer> firstContactByPhone = new HashMap<>();
        Map<String, Integer> firstContactByEmail = new HashMap<>();
        Map<String, Integer> firstContactWithoutPhoneByName = new HashMap<>();
        Map<String, Integer> firstContactWithPhoneByName = new HashMap<>();
        for (int i = 0; i < contacts.size(); i++) {
            ContactInfo contact = contacts.get(i);
            for (String key : emailDuplicateKeys(contact)) {
                Integer firstIndex = firstContactByEmail.putIfAbsent(key, i);
                if (firstIndex != null) {
                    unionFind.union(firstIndex, i);
                }
            }
            for (String key : phoneDuplicateKeys(contact)) {
                Integer firstIndex = firstContactByPhone.putIfAbsent(key, i);
                if (firstIndex != null) {
                    unionFind.union(firstIndex, i);
                }
            }
            String nameKey = normalizedNameKey(contact.name);
            if (nameKey.length() < 4) {
                continue;
            }
            if (contact.phones.isEmpty()) {
                Integer firstWithPhone = firstContactWithPhoneByName.get(nameKey);
                if (firstWithPhone != null) {
                    unionFind.union(firstWithPhone, i);
                }
                firstContactWithoutPhoneByName.putIfAbsent(nameKey, i);
            } else {
                Integer firstWithoutPhone = firstContactWithoutPhoneByName.get(nameKey);
                if (firstWithoutPhone != null) {
                    unionFind.union(firstWithoutPhone, i);
                }
                firstContactWithPhoneByName.putIfAbsent(nameKey, i);
            }
        }
        addDuplicateGroups(unionFind);
    }

    private void addDuplicateGroups(UnionFind unionFind) {
        Map<Integer, List<ContactInfo>> groupsByRoot = new LinkedHashMap<>();
        for (int i = 0; i < contacts.size(); i++) {
            groupsByRoot.computeIfAbsent(unionFind.find(i), root -> new ArrayList<>()).add(contacts.get(i));
        }
        for (List<ContactInfo> group : groupsByRoot.values()) {
            if (group.size() > 1) {
                duplicateGroups.add(group);
            }
        }
    }

    private Set<String> emailDuplicateKeys(ContactInfo contact) {
        Set<String> keys = new HashSet<>();
        for (String email : contact.emails) {
            if (!email.isEmpty()) {
                keys.add(email);
            }
        }
        return keys;
    }

    private Set<String> phoneDuplicateKeys(ContactInfo contact) {
        Set<String> keys = new HashSet<>();
        for (PhoneValue phone : contact.phones) {
            keys.addAll(phoneMatchKeys(phone));
        }
        return keys;
    }

    private String mergeReason(List<ContactInfo> group) {
        Set<String> emails = new HashSet<>();
        Set<String> phones = new HashSet<>();
        Set<String> namesWithPhone = new HashSet<>();
        Set<String> namesWithoutPhone = new HashSet<>();
        for (ContactInfo contact : group) {
            for (String email : emailDuplicateKeys(contact)) {
                if (!emails.add(email)) {
                    return "Reason: same email";
                }
            }
            for (String phone : phoneDuplicateKeys(contact)) {
                if (!phones.add(phone)) {
                    return "Reason: same number";
                }
            }
            String name = normalizedNameKey(contact.name);
            if (name.length() < 4) {
                continue;
            }
            if (contact.phones.isEmpty()) {
                if (namesWithPhone.contains(name)) {
                    return "Reason: same name, one has no number";
                }
                namesWithoutPhone.add(name);
            } else {
                if (namesWithoutPhone.contains(name)) {
                    return "Reason: same name, one has no number";
                }
                namesWithPhone.add(name);
            }
        }
        return "Reason: matching contact data";
    }

    private Set<String> phoneMatchKeys(PhoneValue phone) {
        Set<String> keys = new HashSet<>();
        keys.addAll(phoneMatchKeys(phone.number));
        keys.addAll(phoneMatchKeys(phone.normalized));
        return keys;
    }

    private Set<String> phoneMatchKeys(String number) {
        Set<String> keys = new HashSet<>();
        String digits = digitsOnly(number);
        if (digits.length() < MIN_PHONE_DIGITS) {
            return keys;
        }
        keys.add(digits);
        if (digits.startsWith("00") && digits.length() > 9) {
            keys.add(digits.substring(2));
        }
        if (!currentCountryDigits.isEmpty() && digits.startsWith(currentCountryDigits) && digits.length() > currentCountryDigits.length() + 6) {
            keys.add(digits.substring(currentCountryDigits.length()));
        }
        String localDigits = stripLocalTrunkPrefix(digits);
        if (localDigits.length() >= MIN_PHONE_DIGITS) {
            keys.add(localDigits);
        }
        if (digits.length() > 10) {
            keys.add(lastDigits(digits, 10));
        }
        if (digits.length() > 9) {
            keys.add(lastDigits(digits, 9));
        }
        if (digits.length() > 8) {
            keys.add(lastDigits(digits, 8));
        }
        return keys;
    }

    private String canonicalPhoneKey(PhoneValue phone) {
        String normalizedKey = canonicalPhoneKey(phone.normalized);
        return !normalizedKey.isEmpty() ? normalizedKey : canonicalPhoneKey(phone.number);
    }

    private String canonicalPhoneKey(String number) {
        String digits = digitsOnly(number);
        if (digits.length() < MIN_PHONE_DIGITS) {
            return "";
        }
        if (digits.startsWith("00") && digits.length() > 9) {
            digits = digits.substring(2);
        }
        if (!currentCountryDigits.isEmpty() && digits.startsWith(currentCountryDigits) && digits.length() > currentCountryDigits.length() + 6) {
            digits = digits.substring(currentCountryDigits.length());
        }
        digits = stripLocalTrunkPrefix(digits);
        return digits.length() >= MIN_PHONE_DIGITS ? digits : "";
    }

    private void findEmptyContacts() {
        for (ContactInfo contact : contacts) {
            if (contact.name.isEmpty() && contact.phones.isEmpty() && contact.emails.isEmpty()) {
                emptyContacts.add(contact);
            }
        }
    }

    private void findMissingCountryCodes() {
        String code = currentCountryCode;
        if (!code.startsWith("+") || code.length() < 2) {
            return;
        }
        for (ContactInfo contact : contacts) {
            for (PhoneValue phone : contact.phones) {
                String number = phone.number;
                String digits = digitsOnly(number);
                if (number.startsWith("+") || number.startsWith("00") || digits.length() < MIN_PHONE_DIGITS || digits.length() > 15) {
                    continue;
                }
                String localDigits = stripLocalTrunkPrefix(digits);
                if (localDigits.length() >= MIN_PHONE_DIGITS) {
                    phoneFixes.add(new PhoneFix(contact, phone, code + localDigits));
                }
            }
        }
    }

    private void findDuplicatePhoneValues() {
        for (ContactInfo contact : contacts) {
            Map<String, PhoneValue> firstPhoneByKey = new LinkedHashMap<>();
            for (PhoneValue phone : contact.phones) {
                String key = canonicalPhoneKey(phone);
                if (key.isEmpty()) {
                    continue;
                }
                PhoneValue keptPhone = firstPhoneByKey.putIfAbsent(key, phone);
                if (keptPhone != null) {
                    duplicatePhoneFixes.add(new DuplicatePhoneFix(contact, keptPhone, phone));
                }
            }
        }
    }

    private void findRomanizationFixes() {
        Transliterator transliterator = Transliterator.getInstance("Any-Latin; Latin-ASCII");
        for (ContactInfo contact : contacts) {
            if (contact.name.isEmpty() || hasLatinLetter(contact.name) || !hasNonLatinLetter(contact.name)) {
                continue;
            }
            String romanized = clean(transliterator.transliterate(contact.name));
            if (romanized.isEmpty() || romanized.equals(contact.name) || !hasLatinLetter(romanized)) {
                continue;
            }
            RawContactValue raw = contact.firstRawContactWithoutPhoneticName();
            if (raw != null) {
                romanizationFixes.add(new RomanizationFix(contact, raw, romanized));
            }
        }
    }

    private void renderResults() {
        summary.setText(contacts.size() + " contacts scanned. "
                + duplicateGroups.size() + " merge suggestions, "
                + emptyContacts.size() + " empty contacts, "
                + phoneFixes.size() + " phone numbers missing country code, "
                + duplicatePhoneFixes.size() + " repeated phone numbers, "
                + romanizationFixes.size() + " names ready for romanization.");

        section("Suggested merges");
        if (duplicateGroups.isEmpty()) {
            content.addView(text("No likely duplicates found.", 15, false));
        } else {
            for (List<ContactInfo> group : duplicateGroups) {
                StringBuilder label = new StringBuilder(mergeReason(group));
                for (ContactInfo contact : group) {
                    label.append("\n").append(contact.displayLabel());
                }
                addActionCard(label.toString(), "Merge", v -> confirmMerge(group));
            }
        }

        section("Empty contacts");
        if (emptyContacts.isEmpty()) {
            content.addView(text("No empty contacts found.", 15, false));
        } else {
            for (ContactInfo contact : emptyContacts) {
                addActionCard("Empty contact #" + contact.contactId, "Delete", v -> confirmDelete(contact));
            }
        }

        section("Missing country code");
        if (phoneFixes.isEmpty()) {
            content.addView(text("No local-looking numbers need changes.", 15, false));
        } else {
            addActionCard(phoneFixes.size() + " numbers can be updated to " + currentCountryCode, "Update all", v -> confirmUpdateAllPhoneFixes());
            for (PhoneFix fix : phoneFixes) {
                addActionCard(fix.contact.displayLabel() + "\n" + fix.phone.number + " -> " + fix.newNumber, "Update", v -> confirmPhoneFix(fix));
            }
        }

        section("Repeated phone numbers");
        if (duplicatePhoneFixes.isEmpty()) {
            content.addView(text("No contacts have the same phone number saved more than once.", 15, false));
        } else {
            addActionCard(duplicatePhoneFixes.size() + " repeated phone entries can be removed.", "Delete all", v -> confirmDeleteAllDuplicatePhones());
            for (DuplicatePhoneFix fix : duplicatePhoneFixes) {
                addActionCard(fix.contact.displayLabel() + "\nKeep " + fix.keepPhone.number + "\nDelete " + fix.deletePhone.number, "Delete", v -> confirmDeleteDuplicatePhone(fix));
            }
        }

        section("Romanized names");
        if (romanizationFixes.isEmpty()) {
            content.addView(text("No non-Latin-only display names need romanized names.", 15, false));
            return;
        }
        for (RomanizationFix fix : romanizationFixes) {
            addActionCard(fix.contact.name + "\n" + fix.romanizedName, "Add", v -> confirmRomanizationFix(fix));
        }
    }

    private void confirmMerge(List<ContactInfo> group) {
        new AlertDialog.Builder(this)
                .setTitle("Merge contacts?")
                .setMessage("This links the matching raw contacts into one contact. It does not delete contact data.")
                .setPositiveButton("Merge", (dialog, which) -> mergeGroup(group))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void mergeGroup(List<ContactInfo> group) {
        Set<Long> uniqueRawIds = new LinkedHashSet<>();
        for (ContactInfo contact : group) {
            for (RawContactValue raw : contact.rawContacts) {
                uniqueRawIds.add(raw.rawContactId);
            }
        }
        List<Long> rawIds = new ArrayList<>(uniqueRawIds);
        if (rawIds.size() < 2) {
            Toast.makeText(this, "Nothing to merge for this contact", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        forEachRawContactPair(rawIds, (first, second) -> ops.add(aggregationOperation(ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER, first, second)));
        applyOps(ops, "Contacts merged into one");
    }

    private ContentProviderOperation aggregationOperation(int type, long firstRawContactId, long secondRawContactId) {
        return ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                .withValue(ContactsContract.AggregationExceptions.TYPE, type)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, firstRawContactId)
                .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, secondRawContactId)
                .build();
    }

    private void forEachRawContactPair(List<Long> rawIds, RawContactPairConsumer consumer) {
        for (int i = 0; i < rawIds.size(); i++) {
            for (int j = i + 1; j < rawIds.size(); j++) {
                consumer.accept(rawIds.get(i), rawIds.get(j));
            }
        }
    }

    private void confirmDelete(ContactInfo contact) {
        new AlertDialog.Builder(this)
                .setTitle("Delete empty contact?")
                .setMessage("This deletes the selected empty contact.")
                .setPositiveButton("Delete", (dialog, which) -> deleteContact(contact))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteContact(ContactInfo contact) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
                .withSelection(ContactsContract.RawContacts.CONTACT_ID + "=?", new String[]{String.valueOf(contact.contactId)})
                .build());
        applyOps(ops, "Empty contact deleted");
    }

    private void confirmPhoneFix(PhoneFix fix) {
        new AlertDialog.Builder(this)
                .setTitle("Update phone number?")
                .setMessage(fix.phone.number + " -> " + fix.newNumber)
                .setPositiveButton("Update", (dialog, which) -> updatePhone(fix))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updatePhone(PhoneFix fix) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(phoneUpdateOperation(fix));
        applyOps(ops, "Phone number updated");
    }

    private void confirmUpdateAllPhoneFixes() {
        new AlertDialog.Builder(this)
                .setTitle("Update all missing country codes?")
                .setMessage("This updates " + phoneFixes.size() + " phone numbers to use " + currentCountryCode + ".")
                .setPositiveButton("Update all", (dialog, which) -> updateAllPhoneFixes())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateAllPhoneFixes() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (PhoneFix fix : phoneFixes) {
            ops.add(phoneUpdateOperation(fix));
        }
        applyOps(ops, "Phone numbers updated");
    }

    private ContentProviderOperation phoneUpdateOperation(PhoneFix fix) {
        return ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data._ID + "=?", new String[]{String.valueOf(fix.phone.dataId)})
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, fix.newNumber)
                .build();
    }

    private void confirmDeleteDuplicatePhone(DuplicatePhoneFix fix) {
        new AlertDialog.Builder(this)
                .setTitle("Delete repeated phone number?")
                .setMessage("Keep " + fix.keepPhone.number + "\nDelete " + fix.deletePhone.number)
                .setPositiveButton("Delete", (dialog, which) -> deleteDuplicatePhone(fix))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteDuplicatePhone(DuplicatePhoneFix fix) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(phoneDeleteOperation(fix.deletePhone));
        applyOps(ops, "Repeated phone number deleted");
    }

    private void confirmDeleteAllDuplicatePhones() {
        new AlertDialog.Builder(this)
                .setTitle("Delete all repeated phone numbers?")
                .setMessage("This removes " + duplicatePhoneFixes.size() + " repeated phone entries, keeping one copy of each number.")
                .setPositiveButton("Delete all", (dialog, which) -> deleteAllDuplicatePhones())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteAllDuplicatePhones() {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (DuplicatePhoneFix fix : duplicatePhoneFixes) {
            ops.add(phoneDeleteOperation(fix.deletePhone));
        }
        applyOps(ops, "Repeated phone numbers deleted");
    }

    private ContentProviderOperation phoneDeleteOperation(PhoneValue phone) {
        return ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection(ContactsContract.Data._ID + "=?", new String[]{String.valueOf(phone.dataId)})
                .build();
    }

    private void confirmRomanizationFix(RomanizationFix fix) {
        new AlertDialog.Builder(this)
                .setTitle("Add romanized name?")
                .setMessage(fix.contact.name + " -> " + fix.romanizedName)
                .setPositiveButton("Add", (dialog, which) -> addRomanizedName(fix))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addRomanizedName(RomanizationFix fix) {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        if (fix.rawContact.nameDataId > 0) {
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(ContactsContract.Data._ID + "=?", new String[]{String.valueOf(fix.rawContact.nameDataId)})
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME, fix.romanizedName)
                    .build());
        } else {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValue(ContactsContract.Data.RAW_CONTACT_ID, fix.rawContact.rawContactId)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME, fix.romanizedName)
                    .build());
        }
        applyOps(ops, "Romanized name added");
    }

    private void applyOps(ArrayList<ContentProviderOperation> ops, String message) {
        try {
            applyOpsInChunks(ops);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            scanContacts();
        } catch (OperationApplicationException | RemoteException e) {
            Toast.makeText(this, "Change failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void applyOpsInChunks(ArrayList<ContentProviderOperation> ops) throws RemoteException, OperationApplicationException {
        for (int start = 0; start < ops.size(); start += 100) {
            int end = Math.min(start + 100, ops.size());
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, new ArrayList<>(ops.subList(start, end)));
        }
    }

    private void addActionCard(String body, String action, android.view.View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, dp(6));
        card.setLayoutParams(params);
        card.setBackgroundColor(Color.WHITE);
        card.addView(text(body, 15, false));
        Button button = button(action);
        button.setOnClickListener(listener);
        card.addView(button);
        content.addView(card);
    }

    private void section(String title) {
        TextView view = text(title, 20, true);
        view.setPadding(0, dp(22), 0, dp(4));
        content.addView(view);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(31, 41, 55));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, dp(8));
        return row;
    }

    private boolean hasContactsPermission() {
        return checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String digitsOnly(String value) {
        return clean(value).replaceAll("[^0-9]", "");
    }

    private static String lastDigits(String digits, int count) {
        return digits.length() <= count ? digits : digits.substring(digits.length() - count);
    }

    private static String stripLocalTrunkPrefix(String digits) {
        String value = digits;
        while (value.startsWith("0") && value.length() > MIN_PHONE_DIGITS) {
            value = value.substring(1);
        }
        return value;
    }

    private static String normalizeCountryCode(String value) {
        String cleaned = clean(value);
        if (cleaned.startsWith("+")) {
            return cleaned;
        }
        String digits = digitsOnly(cleaned);
        return digits.isEmpty() ? "" : "+" + digits;
    }

    private static String normalizedNameKey(String name) {
        return clean(name).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static String join(List<String> values, String separator) {
        return String.join(separator, values);
    }

    private static String defaultCountryCode() {
        switch (Locale.getDefault().getCountry()) {
            case "AT":
                return "+43";
            case "CH":
                return "+41";
            case "GB":
                return "+44";
            case "FR":
                return "+33";
            case "ES":
                return "+34";
            case "IT":
                return "+39";
            case "NL":
                return "+31";
            case "PL":
                return "+48";
            case "US":
            case "CA":
                return "+1";
            default:
                return "+49";
        }
    }

    private static boolean hasLatinLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetter(ch) && Character.UnicodeScript.of(ch) == Character.UnicodeScript.LATIN) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonLatinLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetter(ch) && Character.UnicodeScript.of(ch) != Character.UnicodeScript.LATIN) {
                return true;
            }
        }
        return false;
    }

    private static final class ContactInfo {
        long contactId;
        String name = "";
        String lookupKey = "";
        final List<PhoneValue> phones = new ArrayList<>();
        final List<String> emails = new ArrayList<>();
        final List<RawContactValue> rawContacts = new ArrayList<>();

        String displayLabel() {
            String label = name.isEmpty() ? "Unnamed contact" : name;
            List<String> values = new ArrayList<>();
            for (PhoneValue phone : phones) {
                if (!phone.number.isEmpty()) {
                    values.add(phone.number);
                }
            }
            if (!values.isEmpty()) {
                label = label + " | " + join(values, ", ");
            }
            return emails.isEmpty() ? label : label + " | " + emails.get(0);
        }

        RawContactValue findRawContact(long rawContactId) {
            for (RawContactValue raw : rawContacts) {
                if (raw.rawContactId == rawContactId) {
                    return raw;
                }
            }
            return null;
        }

        RawContactValue firstRawContactWithoutPhoneticName() {
            for (RawContactValue raw : rawContacts) {
                if (!raw.hasPhoneticName()) {
                    return raw;
                }
            }
            return null;
        }
    }

    private static final class UnionFind {
        private final int[] parent;

        UnionFind(int size) {
            parent = new int[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        int find(int value) {
            if (parent[value] != value) {
                parent[value] = find(parent[value]);
            }
            return parent[value];
        }

        void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);
            if (firstRoot != secondRoot) {
                parent[secondRoot] = firstRoot;
            }
        }
    }

    private static final class RawContactValue {
        long rawContactId;
        long nameDataId;
        String accountType = "";
        String phoneticGivenName = "";
        String phoneticMiddleName = "";
        String phoneticFamilyName = "";

        boolean hasPhoneticName() {
            return !phoneticGivenName.isEmpty() || !phoneticMiddleName.isEmpty() || !phoneticFamilyName.isEmpty();
        }
    }

    private static final class PhoneValue {
        long dataId;
        String number = "";
        String normalized = "";
    }

    private static final class PhoneFix {
        final ContactInfo contact;
        final PhoneValue phone;
        final String newNumber;

        PhoneFix(ContactInfo contact, PhoneValue phone, String newNumber) {
            this.contact = contact;
            this.phone = phone;
            this.newNumber = newNumber;
        }
    }

    private static final class DuplicatePhoneFix {
        final ContactInfo contact;
        final PhoneValue keepPhone;
        final PhoneValue deletePhone;

        DuplicatePhoneFix(ContactInfo contact, PhoneValue keepPhone, PhoneValue deletePhone) {
            this.contact = contact;
            this.keepPhone = keepPhone;
            this.deletePhone = deletePhone;
        }
    }

    private static final class RomanizationFix {
        final ContactInfo contact;
        final RawContactValue rawContact;
        final String romanizedName;

        RomanizationFix(ContactInfo contact, RawContactValue rawContact, String romanizedName) {
            this.contact = contact;
            this.rawContact = rawContact;
            this.romanizedName = romanizedName;
        }
    }
}
