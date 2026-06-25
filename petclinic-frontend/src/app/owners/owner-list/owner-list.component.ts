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
