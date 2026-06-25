# Owners Multi-Column Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the last-name-only, case-sensitive owners filter with a single search box that matches a term, case-insensitively and as a substring, against first name, last name, address, city, telephone, and pet names.

**Architecture:** Filtering stays on the backend. `OwnerRepository` gets a JPQL `@Query` that `LOWER()`-matches the term against all owner columns plus joined pet names. The controller param `lastName` is renamed to `search`. `openapi.yaml` is regenerated. The Angular service and owner-list component/template are updated for the renamed param and broadened search semantics.

**Tech Stack:** Spring Boot 3.5 / Java 21 / Spring Data JPA / MapStruct (backend); Angular 16 / RxJS / Jasmine+Karma (frontend); embedded PostgreSQL (Zonky) for backend tests.

## Global Constraints

- Java line length ≤ 120 chars.
- Constructor injection in production; `@Autowired` only in tests.
- Match rule: **case-insensitive substring (contains)** across firstName, lastName, address, city, telephone, pet name.
- Empty `search` term returns **all** owners.
- API param is `search` (the old `lastName` param is removed, not kept for back-compat).
- "Find Owner" button triggers search on click (no live/debounced search).
- Backend tests run against embedded PostgreSQL via `@AutoConfigureEmbeddedDatabase(provider = ZONKY)`; seed data comes from `TestData.anOwner()` → Sherlock Holmes overridden in `OwnerTest.before()` to **George Franklin**, address `Baker St 221B`, city `London`, telephone `1234567890`, with one pet named `Rosy`.

---

## File Structure

- `petclinic-backend/.../repository/OwnerRepository.java` — replace derived query with JPQL `searchOwners`.
- `petclinic-backend/.../rest/OwnerRestController.java` — rename `listOwners` param to `search`, call `searchOwners`.
- `petclinic-backend/.../rest/OwnerTest.java` — update 2 existing filter tests, add 6 new column/semantics tests.
- `openapi.yaml` (project root) — regenerated output.
- `petclinic-frontend/.../owners/owner.service.ts` — `searchOwners` uses `?search=`.
- `petclinic-frontend/.../owners/owner.service.spec.ts` — update the search spec.
- `petclinic-frontend/.../owners/owner-list/owner-list.component.ts` — rename field to `searchTerm`, method to `search`.
- `petclinic-frontend/.../owners/owner-list/owner-list.component.html` — relabel input, update empty-state text.
- `petclinic-frontend/.../owners/owner-list/owner-list.component.spec.ts` — update component specs.

---

## Task 1: Backend search query + controller

**Files:**
- Modify: `petclinic-backend/src/main/java/victor/training/petclinic/repository/OwnerRepository.java:12`
- Modify: `petclinic-backend/src/main/java/victor/training/petclinic/rest/OwnerRestController.java:57-62`
- Test: `petclinic-backend/src/test/java/victor/training/petclinic/rest/OwnerTest.java`

**Interfaces:**
- Consumes: `Owner` entity fields `firstName, lastName, address, city, telephone` and `Set<Pet> pets` (each `Pet` has `name`); `OwnerMapper.toOwnerDtoCollection(List<Owner>)`.
- Produces: `OwnerRepository.searchOwners(String search) : List<Owner>`; endpoint `GET /api/owners?search=<term>`.

- [ ] **Step 1: Update the two existing filter tests to the new `search` param**

In `OwnerTest.java`, change the URL in `getAllWithAddressFilter()` (currently line 144) and `getAllWithNameFilter_notFound()` (currently line 165):

```java
    @Test
    void getAllWithLastNameFilter() throws Exception {
        Owner owner2 = TestData.anOwner();
        owner2.setLastName("JavaBeans");
        int owner2Id = ownerRepository.save(owner2).getId();

        List<OwnerDto> owners = search("/api/owners?search=Java");

        assertThat(owners)
            .extracting(OwnerDto::getId, OwnerDto::getLastName)
            .contains(Assertions.tuple(owner2Id, "JavaBeans"));
    }
```

```java
    @Test
    void getAllWithNameFilter_notFound() throws Exception {
        List<OwnerDto> results = search("/api/owners?search=NonExistent");

        assertThat(results).isEmpty();
    }
```

- [ ] **Step 2: Add the new column-match and semantics tests**

Add these methods to `OwnerTest.java` (the `before()` seed owner is George Franklin, address `Baker St 221B`, city `London`, telephone `1234567890`, pet `Rosy`):

```java
    @Test
    void search_byFirstName_caseInsensitiveSubstring() throws Exception {
        List<OwnerDto> owners = search("/api/owners?search=eorg");

        assertThat(owners)
            .extracting(OwnerDto::getId, OwnerDto::getFirstName)
            .contains(Assertions.tuple(ownerId, "George"));
    }

    @Test
    void search_byCity_caseInsensitive() throws Exception {
        List<OwnerDto> owners = search("/api/owners?search=london");

        assertThat(owners)
            .extracting(OwnerDto::getId)
            .contains(ownerId);
    }

    @Test
    void search_byAddress_substring() throws Exception {
        List<OwnerDto> owners = search("/api/owners?search=baker");

        assertThat(owners)
            .extracting(OwnerDto::getId)
            .contains(ownerId);
    }

    @Test
    void search_byTelephone_substring() throws Exception {
        List<OwnerDto> owners = search("/api/owners?search=456789");

        assertThat(owners)
            .extracting(OwnerDto::getId)
            .contains(ownerId);
    }

    @Test
    void search_byPetName_caseInsensitive() throws Exception {
        List<OwnerDto> owners = search("/api/owners?search=rosy");

        assertThat(owners)
            .extracting(OwnerDto::getId)
            .contains(ownerId);
    }

    @Test
    void search_emptyTerm_returnsAll() throws Exception {
        List<OwnerDto> owners = search("/api/owners?search=");

        assertThat(owners)
            .extracting(OwnerDto::getId)
            .contains(ownerId);
    }
```

- [ ] **Step 3: Run the new tests to verify they fail**

Run: `cd petclinic-backend && mvn test -Dtest=OwnerTest`
Expected: FAIL — compile error `cannot find symbol: method searchOwners` is acceptable at this point (repository method does not exist yet), or test failures because the controller still uses `lastName`.

- [ ] **Step 4: Replace the repository derived query with the JPQL search**

In `OwnerRepository.java`, replace line 12 (`List<Owner> findByLastNameStartingWith(String lastName);`) with:

```java
    @Query("""
        SELECT DISTINCT o FROM Owner o LEFT JOIN o.pets p WHERE
          LOWER(o.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.lastName)  LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.address)   LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.city)      LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(o.telephone) LIKE LOWER(CONCAT('%', :search, '%')) OR
          LOWER(p.name)      LIKE LOWER(CONCAT('%', :search, '%'))
        """)
    List<Owner> searchOwners(@Param("search") String search);
```

Add the import for `@Param` near the existing `org.springframework.data.jpa.repository.Query` import:

```java
import org.springframework.data.repository.query.Param;
```

- [ ] **Step 5: Update the controller to use `search`**

In `OwnerRestController.java`, replace the `listOwners` method (lines 57-62) with:

```java
    @Operation(operationId = "listOwners", summary = "List owners")
    @GetMapping(produces = "application/json")
    public List<OwnerDto> listOwners(@RequestParam(name = "search", defaultValue = "") String search) {
        List<Owner> owners = ownerRepository.searchOwners(search);
        return ownerMapper.toOwnerDtoCollection(owners);
    }
```

- [ ] **Step 6: Run the full OwnerTest class to verify all pass**

Run: `cd petclinic-backend && mvn test -Dtest=OwnerTest`
Expected: PASS — all tests green, including `getAll`, the renamed filter tests, and the 6 new tests.

- [ ] **Step 7: Commit**

```bash
git add petclinic-backend/src/main/java/victor/training/petclinic/repository/OwnerRepository.java \
        petclinic-backend/src/main/java/victor/training/petclinic/rest/OwnerRestController.java \
        petclinic-backend/src/test/java/victor/training/petclinic/rest/OwnerTest.java
git commit -m "feat(owners): case-insensitive multi-column search on backend

Replace findByLastNameStartingWith with a JPQL searchOwners query that
matches a term (case-insensitive substring) against firstName, lastName,
address, city, telephone, and pet names. Rename the listOwners param
lastName -> search.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Regenerate openapi.yaml

**Files:**
- Modify (generated): `openapi.yaml` (project root)

**Interfaces:**
- Consumes: running app spec via `OpenApiExtractorTest.generateOpenApiYaml()`.
- Produces: updated `openapi.yaml` where the `listOwners` parameter is named `search`.

- [ ] **Step 1: Run the extractor test to regenerate the spec**

Run: `cd petclinic-backend && mvn test -Dtest=OpenApiExtractorTest`
Expected: PASS. The test writes `../openapi.yaml`.

- [ ] **Step 2: Verify the param rename landed in the spec**

Run: `git -C /Users/tschmidt/Developer/illumiqon/petclinic diff --stat openapi.yaml && grep -n "name: search" openapi.yaml`
Expected: `openapi.yaml` shows as modified and the grep finds the `search` parameter under the owners listing. The old `name: lastName` parameter for `listOwners` is gone.

- [ ] **Step 3: Commit**

```bash
git add openapi.yaml
git commit -m "chore(openapi): regenerate spec for owners search param

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Frontend service uses ?search=

**Files:**
- Modify: `petclinic-frontend/src/app/owners/owner.service.ts:53-61`
- Test: `petclinic-frontend/src/app/owners/owner.service.spec.ts:134-144`

**Interfaces:**
- Consumes: `environment.REST_API_URL + 'owners'` base URL.
- Produces: `OwnerService.searchOwners(search: string) : Observable<Owner[]>` issuing `GET .../owners?search=<term>`.

- [ ] **Step 1: Update the service spec to expect `?search=`**

In `owner.service.spec.ts`, replace the `search owners by last name prefix` test (lines 134-144) with:

```typescript
  it('search owners by term', () => {
    ownerService.searchOwners('Fr').subscribe((owners) => {
      expect(owners).toEqual(expectedOwners);
    });

    const req = httpTestingController.expectOne(
      ownerService.entityUrl + '?search=Fr'
    );
    expect(req.request.method).toEqual('GET');
    req.flush(expectedOwners);
  });
```

- [ ] **Step 2: Run the spec to verify it fails**

Run: `cd petclinic-frontend && npm run test-headless -- --include='**/owner.service.spec.ts'`
Expected: FAIL — the service still builds `?lastName=Fr`, so `expectOne(...?search=Fr)` finds no matching request.

- [ ] **Step 3: Update the service to build `?search=`**

In `owner.service.ts`, replace `searchOwners` (lines 53-61) with:

```typescript
  searchOwners(search: string): Observable<Owner[]> {
    let url = this.entityUrl;
    if (search !== undefined) {
      url += '?search=' + search;
    }
    return this.http
      .get<Owner[]>(url)
      .pipe(catchError(this.handlerError('searchOwners', [])));
  }
```

- [ ] **Step 4: Run the spec to verify it passes**

Run: `cd petclinic-frontend && npm run test-headless -- --include='**/owner.service.spec.ts'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add petclinic-frontend/src/app/owners/owner.service.ts \
        petclinic-frontend/src/app/owners/owner.service.spec.ts
git commit -m "feat(owners): service search uses ?search= query param

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Frontend owner-list component + template

**Files:**
- Modify: `petclinic-frontend/src/app/owners/owner-list/owner-list.component.ts:14,16,41-69` (field `lastName`→`searchTerm`, drop `listOfOwnersWithLastName`, method `searchByLastName`→`search`)
- Modify: `petclinic-frontend/src/app/owners/owner-list/owner-list.component.html:8-25`
- Test: `petclinic-frontend/src/app/owners/owner-list/owner-list.component.spec.ts:109-127`

**Interfaces:**
- Consumes: `OwnerService.getOwners()`, `OwnerService.searchOwners(search)`.
- Produces: `OwnerListComponent.searchTerm` (string field bound to the input) and `OwnerListComponent.search(term: string)` method invoked by the button. The field and method are **deliberately different names** (`searchTerm` vs `search`) — a TS class cannot have a property and a method sharing one name.

- [ ] **Step 1: Update the component specs for the renamed method**

In `owner-list.component.spec.ts`, replace the two `searchByLastName` tests (lines 109-127) with:

```typescript
  it('search should call getOwners for empty term', () => {
    getOwnersSpy.calls.reset();
    searchOwnersSpy.calls.reset();

    component.search('');

    expect(getOwnersSpy).toHaveBeenCalled();
    expect(searchOwnersSpy).not.toHaveBeenCalled();
  });

  it('search should call searchOwners for non-empty term', () => {
    getOwnersSpy.calls.reset();
    searchOwnersSpy.calls.reset();

    component.search('Fr');

    expect(searchOwnersSpy).toHaveBeenCalledWith('Fr');
    expect(getOwnersSpy).not.toHaveBeenCalled();
  });
```

- [ ] **Step 2: Run the component spec to verify it fails**

Run: `cd petclinic-frontend && npm run test-headless -- --include='**/owner-list.component.spec.ts'`
Expected: FAIL — `component.search` is not a function (method is still `searchByLastName`).

- [ ] **Step 3: Rename the component field and method**

In `owner-list.component.ts`: change the field on line 14 from `lastName: string;` to `searchTerm: string;`, and delete the unused `listOfOwnersWithLastName: Owner[];` field (line 16). Replace `searchByLastName` (lines 41-69) with:

```typescript
  search(term: string) {
    if (term === '') {
      this.ownerService.getOwners().subscribe(
        (owners) => {
          this.owners = owners;
        });
      return;
    }
    this.ownerService.searchOwners(term).subscribe(
      (owners) => {
        this.owners = owners;
      },
      (error) => {
        this.owners = null;
      }
    );
  }
```

- [ ] **Step 4: Update the template label, binding, button, and empty-state text**

In `owner-list.component.html`, replace the search form block (lines 8-25 — the `control-group`, the input, the button, and the no-owners message) with:

```html
        <div class="control-group" id="searchGroup">
          <label class="col-sm-2 control-label">Search </label>
          <div class="col-sm-10">
            <input class="form-control" size="30"
                   maxlength="80" id="search" name="search" [(ngModel)]="searchTerm" value=""/> <span class="help-inline"></span>
          </div>
        </div>
      </div>
      <div class="form-group">
        <div class="col-sm-offset-2 col-sm-10">
          <button type="submit" class="btn btn-default" (click)="search(searchTerm)">Find
            Owner</button>
        </div>
      </div>

    </form>

    <div *ngIf="!owners">No owners match "{{searchTerm}}"</div>
```

- [ ] **Step 5: Run the component spec to verify it passes**

Run: `cd petclinic-frontend && npm run test-headless -- --include='**/owner-list.component.spec.ts'`
Expected: PASS.

- [ ] **Step 6: Run the full frontend test suite**

Run: `cd petclinic-frontend && npm run test-headless`
Expected: PASS — no other specs broken by the rename.

- [ ] **Step 7: Commit**

```bash
git add petclinic-frontend/src/app/owners/owner-list/owner-list.component.ts \
        petclinic-frontend/src/app/owners/owner-list/owner-list.component.html \
        petclinic-frontend/src/app/owners/owner-list/owner-list.component.spec.ts
git commit -m "feat(owners): owner-list search box matches all columns

Rename the lastName input/field/method to a generic search, drop the
unused listOfOwnersWithLastName field, and update the label and empty
state to reflect global multi-column search.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] **Backend full suite**

Run: `cd petclinic-backend && mvn test`
Expected: PASS (all guardrail + REST tests green; `openapi.yaml` already committed so no drift).

- [ ] **Frontend full suite**

Run: `cd petclinic-frontend && npm run test-headless`
Expected: PASS.

- [ ] **Manual smoke (optional)**

Start backend + frontend, open `/owners`, type a city / pet name / partial telephone in lower case, click **Find Owner**, confirm matching owners appear.

---

## Notes / Edge Cases

- `LEFT JOIN o.pets p` ensures owners with **no** pets still match on their own columns; an `INNER JOIN` would wrongly drop them.
- `DISTINCT` prevents duplicate owner rows when several of an owner's pets match the term.
- Empty term yields `LIKE '%%'`, matching every owner — preserves the "show all" behavior.
- No DB index work in scope; dataset is small. If it grows, revisit with `pg_trgm` indexes (out of scope here).
