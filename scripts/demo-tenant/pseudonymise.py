"""Dérive une archive de démonstration d'une archive de structure réelle.

Identités remplacées (noms, téléphones, e-mails, pièces d'identité, comptes
bancaires, immatriculations, raison sociale), volumes et flux intacts.
Usage : pseudonymise.py <src_dir> <out_dir> <users_password_hash>
"""
import base64, hashlib, json, os, re, sys, uuid

SRC, OUT, PWD_HASH = sys.argv[1], sys.argv[2], sys.argv[3]

LAST = ['KONAN', 'KOUASSI', 'KOFFI', 'YAO', 'KOUAME', 'N\'GUESSAN', 'BROU', 'ASSI', 'DIABY',
        'TRAORE', 'COULIBALY', 'OUATTARA', 'KONE', 'SANGARE', 'BAMBA', 'TOURE', 'CISSE',
        'DOUMBIA', 'GBAGBO', 'SERY', 'DIGBEU', 'GNAGNE', 'LATH', 'AKA', 'ADOU', 'EHOUMAN',
        'TANOH', 'AMANI', 'BONI', 'KRA', 'DJE', 'ZADI', 'GOHOU', 'TAPE', 'GUEI', 'DOH',
        'BLE', 'ZOUZOUA', 'LOBA', 'BOGA', 'SORO', 'SILUE', 'YEO', 'TUO', 'FOFANA']
FIRST_M = ['Yao', 'Kouadio', 'Koffi', 'Konan', 'Kouamé', 'Adama', 'Moussa', 'Ibrahim',
           'Seydou', 'Lacina', 'Drissa', 'Mamadou', 'Jean', 'Paul', 'Marcel', 'Firmin',
           'Serge', 'Fabrice', 'Désiré', 'Hervé', 'Olivier', 'Patrice', 'Aimé', 'Émile',
           'Brahima', 'Souleymane', 'Bakary', 'Issouf', 'Zié', 'Daouda']
FIRST_F = ['Aya', 'Akissi', 'Adjoua', 'Amenan', 'Affoué', 'Ahou', 'Mariam', 'Fatou',
           'Awa', 'Aminata', 'Kadidja', 'Salimata', 'Marie', 'Thérèse', 'Joséphine',
           'Christine', 'Clarisse', 'Edwige', 'Nadège', 'Rosine', 'Odile', 'Honorine',
           'Nafissatou', 'Rokia', 'Massandjé', 'Sita', 'Ténin', 'Kady', 'Alimata', 'Bintou']
COMPANIES = ['NÉGOCE ATLANTIQUE', 'COMPTOIR DU LITTORAL', 'EXPORT SAVANE', 'MAISON BANDAMA',
             'SOCIÉTÉ DES DEUX FLEUVES', 'UNION DES COMPTOIRS', 'AGRO TRANSIT SA']

def h(s):
    return int(hashlib.sha1(s.encode('utf-8')).hexdigest(), 16)

def fake_person(key, gender=None):
    n = h('p:' + key)
    last = LAST[n % len(LAST)]
    pool = FIRST_F if gender == 'FEMALE' else FIRST_M if gender == 'MALE' else (FIRST_M + FIRST_F)
    first = pool[(n // 97) % len(pool)]
    return last, first

def fake_company(key):
    return COMPANIES[h('c:' + key) % len(COMPANIES)]

def fake_phone(key):
    n = h('t:' + key) % 10 ** 8
    d = f'{n:08d}'
    return f'07 {d[0:2]} {d[2:4]} {d[4:6]} {d[6:8]}'

def fake_digits(key, length):
    return str(h('d:' + key) % 10 ** length).zfill(length)

def new_uuid_json():
    return {'$binary': {'base64': base64.b64encode(uuid.uuid4().bytes).decode(), 'subType': '04'}}

def read(name):
    p = os.path.join(SRC, name)
    if not os.path.exists(p):
        return []
    return [json.loads(l) for l in open(p, encoding='utf-8') if l.strip()]

# ── 1. Dictionnaire des noms ──────────────────────────────────────────
names = {}      # texte original (majuscules) -> remplacement

def register(original, replacement):
    if not original or not original.strip():
        return
    names[original.strip().upper()] = replacement

for m in read('tenant/members.jsonl'):
    key = m.get('code') or m.get('name') or str(m.get('_id'))
    last, first = fake_person(key, m.get('gender'))
    for field, value in (('name', f'{last} {first}'), ):
        pass
    if m.get('name'): register(m['name'], f'{last} {first}')
    if m.get('lastName') and m.get('firstName'):
        register(f"{m['lastName']} {m['firstName']}", f'{last} {first}')
        register(f"{m['firstName']} {m['lastName']}", f'{first} {last}')
for s in read('tenant/suppliers.jsonl'):
    for field in ('name', 'contactName'):
        v = s.get(field)
        if v and v.strip().upper() not in names:
            last, first = fake_person(s.get('code') or v)
            register(v, f'{last} {first}')
for c in read('tenant/customers.jsonl'):
    if c.get('name'):
        register(c['name'], fake_company(c['name']) if c.get('type') == 'COMPANY'
                 else ' '.join(fake_person(c['name'])))
for t in read('control/tenants.jsonl'):
    for c in (t.get('contacts') or []) + ([t['contact']] if t.get('contact') else []):
        if c.get('name'): register(c['name'], ' '.join(fake_person(c['name'])))
for u in read('control/users.jsonl'):
    if u.get('firstName') and u.get('lastName'):
        register(f"{u['firstName']} {u['lastName']}", ' '.join(reversed(fake_person(u['email']))))
        register(f"{u['lastName']} {u['firstName']}", ' '.join(fake_person(u['email'])))

# Noms longs d'abord : « KOUI IBODE MARCELIN » avant « KOUI IBODE ».
ordered = sorted(names, key=len, reverse=True)
NAME_RE = re.compile(r'(?<![A-Za-zÀ-ÿ])(' + '|'.join(re.escape(n) for n in ordered) + r')(?![A-Za-zÀ-ÿ])',
                     re.IGNORECASE) if ordered else None
BRAND_RE = re.compile(r'scoopa+nab', re.IGNORECASE)
EMAIL_RE = re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
PHONE_RE = re.compile(r'(?<!\d)(?:\+225[ ]?)?0[1-9](?:[ .]?\d{2}){4}(?!\d)')

def email_for(addr):
    local = addr.split('@')[0].lower()
    if local in ('admin',) and addr.endswith('cabosse.local'):
        return addr
    local = BRAND_RE.sub('demo', local)
    local = re.sub(r'[^a-z0-9]', '', local) or 'compte'
    return f'{local}@demo.cabosse.local'

def clean_text(s):
    if not isinstance(s, str) or not s:
        return s
    if NAME_RE:
        s = NAME_RE.sub(lambda m: names[m.group(1).upper()], s)
    s = BRAND_RE.sub('Démo', s)
    s = EMAIL_RE.sub(lambda m: email_for(m.group(0)), s)
    s = PHONE_RE.sub(lambda m: fake_phone(m.group(0)), s)
    s = s.replace('NAB-', 'DEM-')
    return s

ID_KEYS = {'idDocNumber', 'number', 'normalizedNumber'}
PLATE_KEYS = {'truckNumber'}
REF_KEYS = {'paymentRef', 'accountNumber', 'rccm', 'taxId'}

def walk(v, key=None, gender=None):
    if isinstance(v, dict):
        g = v.get('gender') or gender
        out = {}
        for k, x in v.items():
            out[k] = walk(x, k, g)
        return out
    if isinstance(v, list):
        return [walk(x, key, gender) for x in v]
    if isinstance(v, str):
        if key in ID_KEYS and re.search(r'\d{4,}', v):
            return 'C' + fake_digits(v, 10)
        if key in PLATE_KEYS and v.strip():
            return f'{fake_digits(v, 4)} GH 01'
        if key in REF_KEYS and re.search(r'\d{4,}', v):
            return re.sub(r'\d', lambda m: fake_digits(v + m.start().__str__(), 1), v)
        return clean_text(v)
    return v

# ── 2. Réécriture ─────────────────────────────────────────────────────
os.makedirs(os.path.join(OUT, 'tenant'), exist_ok=True)
os.makedirs(os.path.join(OUT, 'control'), exist_ok=True)

def write(name, docs):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        for d in docs:
            f.write(json.dumps(d, ensure_ascii=False) + '\n')

for entry in sorted(os.listdir(os.path.join(SRC, 'tenant'))):
    docs = read('tenant/' + entry)
    coll = entry[:-6]
    if coll == 'members':
        for m in docs:
            last, first = fake_person(m.get('code') or m.get('name') or '', m.get('gender'))
            m['lastName'], m['firstName'], m['name'] = last, first, f'{last} {first}'
            m.pop('email', None)
    docs = [walk(d) for d in docs]
    write('tenant/' + entry, docs)

for entry in sorted(os.listdir(os.path.join(SRC, 'control'))):
    docs = read('control/' + entry)
    coll = entry[:-6]
    if coll == 'tenants':
        for t in docs:
            t['name'] = 'Coopérative Démonstration'
            legal = t.get('legal') or {}
            legal['legalName'] = 'COOPÉRATIVE DÉMONSTRATION COOP-CA'
            t['legal'] = legal
            addr = t.get('address') or {}
            addr['street'] = 'Route de la gare'
            addr['postalCode'] = 'BP 100'
            t['address'] = addr
            b = t.get('branding') or {}
            for k in ('logoFileId', 'mimeType', 'sizeBytes'):
                b.pop(k, None)
            t['branding'] = b
    elif coll == 'users':
        for u in docs:
            u['_id'] = new_uuid_json()
            u['passwordHash'] = PWD_HASH
            last, first = fake_person(u.get('email') or '')
            u['firstName'], u['lastName'] = first, last
    else:
        for d in docs:
            d['_id'] = new_uuid_json()
    docs = [walk(d) for d in docs]
    write('control/' + entry, docs)

manifest = json.load(open(os.path.join(SRC, 'manifest.json'), encoding='utf-8'))
manifest['tenantName'] = 'Coopérative Démonstration'
manifest['tenantSlug'] = 'demo-coop'
json.dump(manifest, open(os.path.join(OUT, 'manifest.json'), 'w', encoding='utf-8'),
          ensure_ascii=False, indent=2)

if os.path.isdir(os.path.join(SRC, 'files')):
    import shutil
    shutil.copytree(os.path.join(SRC, 'files'), os.path.join(OUT, 'files'), dirs_exist_ok=True)

print(f'noms remplacés : {len(names)}')
