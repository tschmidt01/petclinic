# Owners List — Sorting & Pagination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the owners list sortable (asc/desc by any scalar column) and paginated (5/10/20 rows per page with prev/next navigation), server-side.

**Architecture:** Backend `GET /api/owners` returns a Spring `Page<OwnerDto>` instead of a flat list; the search repository query gains a `Pageable` and swaps its pet-name `LEFT JOIN` for an `EXISTS` subquery so paging/sorting/count all run in SQL. The Angular owners grid (Bootstrap 3) reads the paged response and renders clickable sort headers plus a pager.

**Tech Stack:** Spring Boot 3.5 / Java 21 / Spring Data JPA / MapStruct (backend); Angular 16 / RxJS / Karma+Jasmine (frontend).

## Global Constraints

- Java 21; keep line length ≤ 120 chars.
- Constructor injection in production (`@RequiredArgsConstructor`); `@Autowired` only in tests.
- MapStruct for DTO mapping; no service layer (controllers use repositories directly).
- `@Validated` on `@RequestBody`; global exception handling via `@RestControllerAdvice`.
- Run tests after every change; commit frequently (one commit per task).
- Backend tests: embedded Postgres auto-starts, no setup. Run with `mvn` from `petclinic-backend/`.
- Frontend tests: run with `npm run test-headless` from `petclinic-frontend/`.

## Breaking-Change Note (read before starting)

Changing `GET /api/owners` from `List<OwnerDto>` to `Page<OwnerDto>` changes the JSON
shape (items move under `content`). Every consumer of that shape must be updated in the
same change set. Confirmed consumers (verified by grep):

- `OwnerTest.java` — `search()` helper parses `List<OwnerDto>` (Task 2).
- `OwnerSearchThroughLatencyProxyTest.java` — perf assertion on `$` (Task 2).
- `OwnerSteps.java` + `owners.feature` — functional jsonPath glue on `lastName` / `$` (Task 2).
- `OwnerCreateTest.java:98` — calls `ownerRepository.searchOwners("Tesla")` (Task 1).
- `owner.service.ts` + its spec, `owner-list.component.ts` + its spec (Tasks 3–4).
- Generated `openapi.yaml` + `api-types.ts` — regenerated in Task 5.

Confirmed NOT affected (status-only or different path, leave alone):
`BasicAuthenticationConfigTest` (status only), `ValidationErrorRenderingTest` (POST),
`PetSteps` (`/{id}` paths only).

## File Structure

| File | Change |
|------|--------|
| `petclinic-backend/.../repository/OwnerRepository.java` | `searchOwners` → `Page<Owner> searchOwners(String, Pageable)`, EXISTS query |
| `petclinic-backend/.../rest/OwnerRestController.java` | `listOwners` → returns `Page<OwnerDto>`, `Pageable` param, sort whitelist |
| `petclinic-backend/.../rest/OwnerTest.java` | `search()` helper + new paging/sort tests |
| `petclinic-backend/.../rest/OwnerCreateTest.java` | fix `searchOwners` call signature |
| `petclinic-backend/.../perf/OwnerSearchThroughLatencyProxyTest.java` | assert on `$.content` |
| `petclinic-backend/.../functional/OwnerSteps.java` | jsonPath glue reads `content.*` |
| `openapi.yaml` + `petclinic-frontend/src/app/generated/api-types.ts` | regenerated |
| `petclinic-frontend/src/app/owners/owner.service.ts` + `.spec.ts` | paged `getOwners` |
| `petclinic-frontend/src/app/owners/owner-list/owner-list.component.ts` + `.html` + `.spec.ts` | state, sort, pager |

---

### Task 1: Repository — paged & sorted search

**Files:**
- Modify: `petclinic-backend/src/main/java/victor/training/petclinic/repository/OwnerRepository.java`
- Modify: `petclinic-backend/src/test/java/victor/training/petclinic/rest/OwnerCreateTest.java:98`
- Test: `petclinic-backend/src/test/java/victor/training/petclinic/repository/OwnerRepositoryPagingTest.java` (create)

**Interfaces:**
- Produces: `Page<Owner> searchOwners(@Param("search") String search, Pageable pageable)`

- [ ] **Step 1: Write the failing repository test**

Create `petclinic-backend/src/test/java/victor/training/petclinic/repository/OwnerRepositoryPagingTest.java`:

```java
package victor.training.petclinic.repository;

import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import victor.training.petclinic.model.Owner;

import jakarta.transaction.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureEmbeddedDatabase(provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY)
@Transactional
class OwnerRepositoryPagingTest {

    @Autowired
    OwnerRepository ownerRepository;

    @BeforeEach
    void seed() {
        for (String last : new String[] {"Zulu", "Alpha", "Mike"}) {
            Owner o = new Owner();
            o.setFirstName("Test");
            o.setLastName(last);
            o.setAddress("addr");
            o.setCity("city");
            o.setTelephone("0000000000");
            ownerRepository.save(o);
        }
    }

    @Test
    void paginates_andReportsTotal() {
        Page<Owner> page = ownerRepository.searchOwners("Test",
            PageRequest.of(0, 2, Sort.by("lastName").ascending()));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void sortsDescending() {
        Page<Owner> page = ownerRepository.searchOwners("Test",
            PageRequest.of(0, 10, Sort.by("lastName").descending()));

        assertThat(page.getContent().get(0).getLastName())
            .isGreaterThanOrEqualTo(page.getContent().get(1).getLastName());
    }
}
```

- [ ] **Step 2: Run it to confirm it fails to compile**

Run: `cd petclinic-backend && mvn -q test -Dtest=OwnerRepositoryPagingTest`
Expected: compile failure — `searchOwners(String, Pageable)` does not exist.

- [ ] **Step 3: Change the repository method**

In `OwnerRepository.java` add imports and replace the `searchOwners` query/signature:

```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

```java
@Query("""
    SELECT o FROM Owner o WHERE
      LOWER(o.firstName) LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.lastName)  LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.address)   LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.city)      LIKE LOWER(CONCAT('%', :search, '%')) OR
      LOWER(o.telephone) LIKE LOWER(CONCAT('%', :search, '%')) OR
      EXISTS (SELECT 1 FROM Pet p WHERE p.owner = o
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
Page<Owner> searchOwners(@Param("search") String search, Pageable pageable);
```

Remove the now-unused `import java.util.List;` only if no other method in the file uses `List` (it does not after this change — verify and remove).

- [ ] **Step 4: Fix the other caller so the module compiles**

In `OwnerCreateTest.java`, add import `org.springframework.data.domain.Pageable;` and change line 98:

```java
assertThat(ownerRepository.searchOwners("Tesla", Pageable.unpaged()).getContent()).isNotEmpty();
```

- [ ] **Step 5: Run the repository test to verify it passes**

Run: `cd petclinic-backend && mvn -q test -Dtest=OwnerRepositoryPagingTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add petclinic-backend/src/main/java/victor/training/petclinic/repository/OwnerRepository.java \
        petclinic-backend/src/test/java/victor/training/petclinic/repository/OwnerRepositoryPagingTest.java \
        petclinic-backend/src/test/java/victor/training/petclinic/rest/OwnerCreateTest.java
git commit -m "feat(owners): paginate & sort owner search at repository layer

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Controller — return Page<OwnerDto> with sort whitelist

**Files:**
- Modify: `petclinic-backend/src/main/java/victor/training/petclinic/rest/OwnerRestController.java:57-62`
- Modify: `petclinic-backend/src/test/java/victor/training/petclinic/rest/OwnerTest.java`
- Modify: `petclinic-backend/src/test/java/victor/training/petclinic/perf/OwnerSearchThroughLatencyProxyTest.java:60-64`
- Modify: `petclinic-backend/src/test/java/victor/training/petclinic/functional/OwnerSteps.java`

**Interfaces:**
- Consumes: `Page<Owner> searchOwners(String, Pageable)` from Task 1.
- Produces: `GET /api/owners?search=&page=&size=&sort=field,dir` → `Page<OwnerDto>` JSON (`content`, `totalElements`, `totalPages`, `number`, `size`). Allowed sort fields: `firstName, lastName, address, city, telephone`. Unknown field → `400`. Default: page 0, size 10, sort `lastName,asc` with `firstName` asc tiebreak.

- [ ] **Step 1: Update the OwnerTest `search()` helper to parse a Page**

In `OwnerTest.java`, replace the `search(String)` helper (lines 151-161) so it reads the `content` array. Add import `com.fasterxml.jackson.databind.JsonNode;`:

```java
private List<OwnerDto> search(String uriTemplate) throws Exception {
    String responseJson = mockMvc.perform(get(uriTemplate))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        .andReturn()
        .getResponse()
        .getContentAsString();

    JsonNode content = mapper.readTree(responseJson).get("content");
    return mapper.convertValue(content, new TypeReference<List<OwnerDto>>() {});
}
```

This keeps all existing search/get-all tests working unchanged.

- [ ] **Step 2: Add failing tests for paging, sorting, and invalid sort**

Append these tests to `OwnerTest.java` (add import `com.fasterxml.jackson.databind.JsonNode;` if not already added above):

```java
@Test
void list_isPaged_withSizeAndTotals() throws Exception {
    // seed a couple more so there is at least a second page at size 1
    Owner extra = TestData.anOwner();
    extra.setLastName("Aaronson");
    ownerRepository.save(extra);

    String json = mockMvc.perform(get("/api/owners?page=0&size=1"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    JsonNode root = mapper.readTree(json);
    assertThat(root.get("content").size()).isEqualTo(1);
    assertThat(root.get("size").asInt()).isEqualTo(1);
    assertThat(root.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
    assertThat(root.get("totalPages").asInt()).isGreaterThanOrEqualTo(2);
}

@Test
void list_sortsByLastNameDescending() throws Exception {
    List<OwnerDto> owners = search("/api/owners?sort=lastName,desc");

    assertThat(owners).isSortedAccordingTo(
        (a, b) -> b.getLastName().compareTo(a.getLastName()));
}

@Test
void list_invalidSortField_isBadRequest() throws Exception {
    mockMvc.perform(get("/api/owners?sort=ssn,asc"))
        .andExpect(status().isBadRequest());
}
```

Run: `cd petclinic-backend && mvn -q test -Dtest=OwnerTest`
Expected: FAIL — endpoint still returns a List (helper finds no `content` node) / no 400 for bad sort.

- [ ] **Step 3: Rewrite the controller endpoint**

In `OwnerRestController.java` add imports:

```java
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import victor.training.petclinic.rest.dto.OwnerDto;
```

Replace `listOwners` (lines 57-62) with:

```java
private static final Set<String> SORTABLE_FIELDS =
    Set.of("firstName", "lastName", "address", "city", "telephone");

@Operation(operationId = "listOwners", summary = "List owners")
@GetMapping(produces = "application/json")
public Page<OwnerDto> listOwners(
        @RequestParam(name = "search", defaultValue = "") String search,
        @PageableDefault(size = 10, sort = "lastName") Pageable pageable) {
    Pageable sanitized = withValidatedSort(pageable);
    Page<Owner> owners = ownerRepository.searchOwners(search, sanitized);
    return owners.map(ownerMapper::toOwnerDto);
}

private Pageable withValidatedSort(Pageable pageable) {
    for (Sort.Order order : pageable.getSort()) {
        if (!SORTABLE_FIELDS.contains(order.getProperty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unsortable field: " + order.getProperty());
        }
    }
    Sort withTiebreak = pageable.getSort().and(Sort.by("firstName").ascending());
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), withTiebreak);
}
```

Remove the now-unused `import java.util.List;` if no other method in the controller returns `List` (several do — keep it).

- [ ] **Step 4: Run OwnerTest to verify it passes**

Run: `cd petclinic-backend && mvn -q test -Dtest=OwnerTest`
Expected: PASS (all existing + 3 new tests).

- [ ] **Step 5: Fix the perf test assertion**

In `OwnerSearchThroughLatencyProxyTest.java`, change the body assertion (line ~63):

```java
.andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(10))));
```

(The test is `@EnabledIf` the latency proxy is reachable, so it is skipped on CI; the edit keeps it correct when run locally.)

- [ ] **Step 6: Fix the functional step glue**

In `OwnerSteps.java`, update the three steps that read the list shape so they read `content`:

```java
@Then("the owner is searchable by last name {string}")
public void theOwnerIsSearchableByLastName(String lastName) {
    var response = RestAssured.given()
        .baseUri(http.baseUri())
        .get("/api/owners?search=" + lastName);
    assertThat(response.statusCode()).isEqualTo(200);
    List<String> lastNames = response.jsonPath().getList("content.lastName", String.class);
    assertThat(lastNames).contains(lastName);
}

@Then("the response JSON array has size {int}")
public void theResponseJsonArrayHasSize(int expected) {
    assertThat(http.getLastResponse().jsonPath().getList("content").size()).isEqualTo(expected);
}

@Then("every item in the response has {string} equal to {string}")
public void everyItemInTheResponseHasFieldEqualTo(String field, String value) {
    List<String> values = http.getLastResponse().jsonPath().getList("content." + field, String.class);
    assertThat(values).isNotEmpty();
    assertThat(values).allMatch(v -> v.equals(value));
}
```

Leave `owners.feature` text unchanged — only the glue changed. Steps `iFetchTheOwner`/`theOwnerHasNPets`/`thePetAtIndex...` hit `/api/owners/{id}` (single owner, not paged) and stay as-is.

- [ ] **Step 7: Run the functional + full owner test suite**

Run: `cd petclinic-backend && mvn -q test -Dtest=OwnerTest,OwnerCreateTest,OwnerRepositoryPagingTest,RunCucumberTest`
(If the Cucumber runner has a different class name, find it: `grep -rl "@Suite\|Cucumber" petclinic-backend/src/test/java | head`.)
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add petclinic-backend/src/main/java/victor/training/petclinic/rest/OwnerRestController.java \
        petclinic-backend/src/test/java/victor/training/petclinic/rest/OwnerTest.java \
        petclinic-backend/src/test/java/victor/training/petclinic/perf/OwnerSearchThroughLatencyProxyTest.java \
        petclinic-backend/src/test/java/victor/training/petclinic/functional/OwnerSteps.java
git commit -m "feat(owners): return paginated, sorted Page<OwnerDto> from list endpoint

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Frontend service — paged getOwners

**Files:**
- Modify: `petclinic-frontend/src/app/owners/owner.service.ts`
- Modify: `petclinic-frontend/src/app/owners/owner.service.spec.ts`

**Interfaces:**
- Consumes: `OwnerPage` from `petclinic-frontend/src/app/owners/owner-page.ts`.
- Produces: `getOwners(search: string, page: number, size: number, sort: string): Observable<OwnerPage>`. Removes the old `getOwners()` and `searchOwners()`.

- [ ] **Step 1: Rewrite the service spec test (failing)**

In `owner.service.spec.ts`, replace the first test (lines 51-59) and the last test (lines 134-144) with a single paged test. Add near the top (after `expectedOwners`):

```typescript
import { OwnerPage } from './owner-page';

const expectedPage: OwnerPage = {
  content: expectedOwners,
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 10,
};
```

Replace the "should return expected owners" test with:

```typescript
it('should request a page of owners with search/page/size/sort params', () => {
  ownerService.getOwners('Fr', 0, 10, 'lastName,asc')
    .subscribe((page) => expect(page).toEqual(expectedPage), fail);

  const req = httpTestingController.expectOne(
    (r) =>
      r.url === ownerService.entityUrl &&
      r.params.get('search') === 'Fr' &&
      r.params.get('page') === '0' &&
      r.params.get('size') === '10' &&
      r.params.get('sort') === 'lastName,asc'
  );
  expect(req.request.method).toEqual('GET');
  req.flush(expectedPage);
});
```

Delete the old "search owners by term" test (it called the removed `searchOwners`).

Run: `cd petclinic-frontend && npm run test-headless`
Expected: FAIL — `getOwners` signature mismatch / `searchOwners` removed reference.

- [ ] **Step 2: Rewrite the service**

In `owner.service.ts`, add imports and replace `getOwners()` + `searchOwners()`:

```typescript
import { HttpClient, HttpParams } from '@angular/common/http';
import { OwnerPage } from './owner-page';
```

```typescript
private static readonly EMPTY_PAGE: OwnerPage = {
  content: [], totalElements: 0, totalPages: 0, number: 0, size: 0,
};

getOwners(search: string, page: number, size: number, sort: string): Observable<OwnerPage> {
  const params = new HttpParams()
    .set('search', search ?? '')
    .set('page', page)
    .set('size', size)
    .set('sort', sort);
  return this.http
    .get<OwnerPage>(this.entityUrl, { params })
    .pipe(catchError(this.handlerError('getOwners', OwnerService.EMPTY_PAGE)));
}
```

Delete the old `getOwners()` and `searchOwners()` methods.

- [ ] **Step 3: Run the service spec to verify it passes**

Run: `cd petclinic-frontend && npm run test-headless`
Expected: OwnerService specs PASS. (The owner-list component spec will still fail — fixed in Task 4.)

- [ ] **Step 4: Commit**

```bash
git add petclinic-frontend/src/app/owners/owner.service.ts \
        petclinic-frontend/src/app/owners/owner.service.spec.ts
git commit -m "feat(owners): paged getOwners in owner service

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Frontend grid — sort headers & pager

**Files:**
- Modify: `petclinic-frontend/src/app/owners/owner-list/owner-list.component.ts`
- Modify: `petclinic-frontend/src/app/owners/owner-list/owner-list.component.html`
- Modify: `petclinic-frontend/src/app/owners/owner-list/owner-list.component.spec.ts`

**Interfaces:**
- Consumes: `getOwners(search, page, size, sort)` from Task 3; `OwnerPage`.
- Produces: component API used by the template — getter `owners: Owner[]`, `page: OwnerPage`, `pageIndex`, `size`, `sizeOptions`, `sortField`, `sortDir`, methods `search(term)`, `sort(field)`, `arrow(field)`, `changeSize(size)`, `prevPage()`, `nextPage()`.

- [ ] **Step 1: Rewrite the component spec (failing)**

Replace `owner-list.component.spec.ts` stub + tests. Change the stub (lines 25-33) and the spy setup (lines 82-87), and the two `search` tests (109-127):

```typescript
import { OwnerPage } from '../owner-page';
```

```typescript
class OwnerServiceStub {
  getOwners(search: string, page: number, size: number, sort: string): Observable<OwnerPage> {
    return of();
  }
}
```

Spy setup (replace the `getOwnersSpy`/`searchOwnersSpy` block):

```typescript
const testPage: OwnerPage = {
  content: testOwners, totalElements: 1, totalPages: 1, number: 0, size: 10,
};
getOwnersSpy = spyOn(ownerService, 'getOwners').and.returnValue(of(testPage));
```

Remove the `searchOwnersSpy` declaration (line 41) and its usages. Replace the two search tests with:

```typescript
it('search calls getOwners with the term and resets to page 0', () => {
  getOwnersSpy.calls.reset();
  component.search('Fr');
  expect(getOwnersSpy).toHaveBeenCalledWith('Fr', 0, component.size, 'lastName,asc');
});

it('sort toggles direction on the same field', () => {
  component.sort('lastName');            // already default asc -> desc
  expect(component.sortDir).toBe('desc');
  component.sort('city');                // new field -> asc
  expect(component.sortField).toBe('city');
  expect(component.sortDir).toBe('asc');
});
```

The "should show full name" test (99-107) stays — it relies on `.ownerFullName`, which the template keeps.

Run: `cd petclinic-frontend && npm run test-headless`
Expected: FAIL — component has no `sort`/`sortDir`/new `getOwners` call shape yet.

- [ ] **Step 2: Rewrite the component class**

Replace the body of `owner-list.component.ts`:

```typescript
import { Component, OnInit } from '@angular/core';
import { OwnerService } from '../owner.service';
import { Owner } from '../owner';
import { OwnerPage } from '../owner-page';
import { Router } from '@angular/router';
import { finalize } from 'rxjs/operators';

@Component({
  selector: 'app-owner-list',
  templateUrl: './owner-list.component.html',
  styleUrls: ['./owner-list.component.css'],
})
export class OwnerListComponent implements OnInit {
  errorMessage: string;
  searchTerm = '';
  page: OwnerPage = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 10 };
  pageIndex = 0;
  size = 10;
  sizeOptions = [5, 10, 20];
  sortField = 'lastName';
  sortDir: 'asc' | 'desc' = 'asc';
  isOwnersDataReceived = false;

  constructor(private router: Router, private ownerService: OwnerService) {}

  ngOnInit() {
    this.load();
  }

  load() {
    const sort = `${this.sortField},${this.sortDir}`;
    this.ownerService
      .getOwners(this.searchTerm, this.pageIndex, this.size, sort)
      .pipe(finalize(() => (this.isOwnersDataReceived = true)))
      .subscribe(
        (page) => (this.page = page),
        (error) => (this.errorMessage = error as any)
      );
  }

  get owners(): Owner[] {
    return this.page.content;
  }

  search(term: string) {
    this.searchTerm = term;
    this.pageIndex = 0;
    this.load();
  }

  sort(field: string) {
    if (this.sortField === field) {
      this.sortDir = this.sortDir === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortField = field;
      this.sortDir = 'asc';
    }
    this.pageIndex = 0;
    this.load();
  }

  arrow(field: string): string {
    if (this.sortField !== field) {
      return '';
    }
    return this.sortDir === 'asc' ? '▲' : '▼';
  }

  changeSize(size: number) {
    this.size = +size;
    this.pageIndex = 0;
    this.load();
  }

  prevPage() {
    if (this.pageIndex > 0) {
      this.pageIndex--;
      this.load();
    }
  }

  nextPage() {
    if (this.pageIndex + 1 < this.page.totalPages) {
      this.pageIndex++;
      this.load();
    }
  }

  onSelect(owner: Owner) {
    this.router.navigate(['/owners', owner.id]);
  }

  addOwner() {
    this.router.navigate(['/owners/add']);
  }
}
```

- [ ] **Step 3: Update the template**

In `owner-list.component.html`, replace the `<thead>` (lines 28-36), the empty-state line (25), and add a pager after the table. Header row:

```html
<thead>
<tr>
  <th style="cursor:pointer" (click)="sort('lastName')">Name <span>{{ arrow('lastName') }}</span></th>
  <th style="cursor:pointer" (click)="sort('address')">Address <span>{{ arrow('address') }}</span></th>
  <th style="cursor:pointer" (click)="sort('city')">City <span>{{ arrow('city') }}</span></th>
  <th style="cursor:pointer" (click)="sort('telephone')">Telephone <span>{{ arrow('telephone') }}</span></th>
  <th>Pets</th>
</tr>
</thead>
```

Change the empty-state line 25 from `*ngIf="!owners"` to:

```html
<div *ngIf="isOwnersDataReceived && page.totalElements === 0">No owners match "{{searchTerm}}"</div>
```

Change line 26 `*ngIf="owners"` to `*ngIf="page.totalElements > 0"`.

Add a pager block immediately before the closing `</div>` of `#ownersTable` (after the Add Owner button div, line 55):

```html
<div class="form-inline" style="margin-top:10px">
  <button class="btn btn-default" (click)="prevPage()" [disabled]="pageIndex === 0">Prev</button>
  <span style="margin:0 10px">Page {{ page.number + 1 }} of {{ page.totalPages }}</span>
  <button class="btn btn-default" (click)="nextPage()"
          [disabled]="pageIndex + 1 >= page.totalPages">Next</button>
  <label style="margin-left:15px">Rows per page
    <select class="form-control" [ngModel]="size" (ngModelChange)="changeSize($event)">
      <option *ngFor="let opt of sizeOptions" [ngValue]="opt">{{ opt }}</option>
    </select>
  </label>
</div>
```

- [ ] **Step 4: Run the frontend tests**

Run: `cd petclinic-frontend && npm run test-headless`
Expected: PASS (all owner-list + owner.service specs).

- [ ] **Step 5: Commit**

```bash
git add petclinic-frontend/src/app/owners/owner-list/owner-list.component.ts \
        petclinic-frontend/src/app/owners/owner-list/owner-list.component.html \
        petclinic-frontend/src/app/owners/owner-list/owner-list.component.spec.ts
git commit -m "feat(owners): sortable headers and pager on owners grid

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Regenerate OpenAPI spec & API types

**Files:**
- Modify (generated): `openapi.yaml`
- Modify (generated): `petclinic-frontend/src/app/generated/api-types.ts`

**Interfaces:** none (generated artifacts must match the new `listOwners` response).

- [ ] **Step 1: Regenerate openapi.yaml**

The spec is produced by `OpenApiExtractorTest`. Run it:

Run: `cd petclinic-backend && mvn -q test -Dtest=OpenApiExtractorTest`
Expected: PASS; `openapi.yaml` at repo root is rewritten with the `listOwners` paged response.

- [ ] **Step 2: Confirm the spec changed**

Run: `git diff --stat openapi.yaml`
Expected: `openapi.yaml` shows changes around the `/api/owners` GET response schema (now a Page object).

- [ ] **Step 3: Regenerate the frontend API types**

Run: `cd petclinic-frontend && npm run generate:api`
Expected: `src/app/generated/api-types.ts` regenerated from the new `openapi.yaml`.

- [ ] **Step 4: Lint the spec (guardrail)**

Run: `cd petclinic-frontend && npm run lint:openapi`
Expected: no errors (exit 0). If Spectral flags the new schema, address the specific rule it names.

- [ ] **Step 5: Build the frontend to confirm types compile**

Run: `cd petclinic-frontend && npm run build`
Expected: build succeeds.

- [ ] **Step 6: Commit**

```bash
git add openapi.yaml petclinic-frontend/src/app/generated/api-types.ts
git commit -m "chore(owners): regenerate OpenAPI spec and API types for paged owners

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Full regression & guardrails

**Files:** none (verification only).

- [ ] **Step 1: Run the full backend test suite**

Run: `cd petclinic-backend && mvn -q test`
Expected: BUILD SUCCESS. This includes guardrail/drift tests (e.g. `OpenApiExtractorTest`, `JpaMatchesDBSchemaTest`) — they must pass with the regenerated spec.

- [ ] **Step 2: Run the full frontend test suite**

Run: `cd petclinic-frontend && npm run test-headless`
Expected: all specs PASS.

- [ ] **Step 3: Manual smoke (optional but recommended)**

Start backend + frontend (`./start-database.sh`, `./start-backend.sh`, `./start-frontend.sh`), open `http://localhost:4200/owners`, and verify: default shows 10 rows sorted by last name; clicking a header toggles asc/desc arrow; changing rows-per-page to 5/20 works; Prev/Next navigate and disable at ends; search resets to page 1 and keeps size/sort.

- [ ] **Step 4: Final commit (only if Step 3 produced fixes)**

```bash
git add -A && git commit -m "test(owners): verify pagination & sorting regression

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

- **Spec coverage:** Backend Page endpoint + Pageable + sort whitelist + tiebreak (Tasks 1-2) ✓; EXISTS query rewrite (Task 1) ✓; frontend paged service (Task 3) ✓; sort headers + pager + 5/10/20 + prev/next (Task 4) ✓; defaults size 10 / lastName asc (Task 2 `@PageableDefault`, Task 4 initial state) ✓; Pets column not sortable (Task 4 — plain `<th>`) ✓.
- **Placeholder scan:** none — every code step has full content.
- **Type consistency:** `searchOwners(String, Pageable): Page<Owner>` used identically in Tasks 1-2 and `OwnerCreateTest`; `getOwners(search,page,size,sort): Observable<OwnerPage>` used identically in Tasks 3-4; `OwnerPage` fields match `owner-page.ts`.
- **Discovered scope beyond spec:** the list-shape change forces updates to perf/functional/create backend tests and regenerated OpenAPI + api-types (Tasks 2 & 5) — folded into the relevant tasks.
