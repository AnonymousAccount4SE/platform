import { Component, OnInit, Input } from '@angular/core';
import { Table } from '../../model';
import { LinksComponent } from '../links/links.component';
import { DashjoinWidget } from '../widget-registry';

/**
 * table widget
 */
@DashjoinWidget({
  name: 'table',
  category: 'Default',
  description: 'Component that displays a table',
  htmlTag: 'dj-table',
  fields: ['title', 'database', 'query', 'arguments']
})
@Component({
  selector: 'app-table',
  templateUrl: './table.component.html',
  styleUrls: ['./table.component.css']
})
export class TableComponent extends LinksComponent implements OnInit {

  /**
   * default row display
   */
  @Input() rows = 10;

  /**
   * default pagination
   */
  @Input() pagination = true;

  /**
   * get schema and call doLayout
   */
  async ngOnInit() {
    try {
      super.ngOnInit();

      this.meta = await this.getData().getMeta();
      this.queryMeta = this.meta.schema as Table;
    } catch (e) {
      this.errorHandler(e);
    }
  }

  /**
   * trigger initial display
   */
  initWidget() {
    try {
      this.page({ pageIndex: 0, pageSize: this.rows, length: null });
    } catch (e) {
      this.errorHandler(e);
    }
  }
}