import { ChangeDetectorRef, Component, ElementRef, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { Schema } from '@dashjoin/json-schema-form';
import { DJBaseComponent } from '../../djbase/djbase.component';
import { DashjoinWidget } from '../widget-registry';

/**
 * chart (Pie, Line, or Bar charts)
 */
@DashjoinWidget({
  name: 'chart',
  category: 'Default',
  description: 'Component that draws a chart (Pie, Line, or Bar charts)',
  htmlTag: 'dj-chart',
  fields: ['title', 'database', 'query', 'arguments', 'chart']
})
@Component({
  selector: 'app-chart',
  templateUrl: './chart.component.html',
  styleUrls: ['./chart.component.css']
})
export class ChartComponent extends DJBaseComponent implements OnInit {

  /**
   * get data and generate chart data
   */
  async initWidget() {
    try {
      await this.page({ pageIndex: 0, pageSize: 50, length: null });
      this.prepareDataForChart();
    } catch (e) {
      this.errorHandler(e);
    }
  }

  /**
   * generate data / label objects for chart
   */
  prepareDataForChart() {

    // this.all = [{ name: 'bus', count: 3, other: 6 }, { name: 'car', count: 2, other: 5 }, { name: null, count: 4 }]

    const types = this.checkColumns(this.all);
    if (!(Object.values(types)[0] === 'string' && Object.values(types)[1] !== 'string')) {
      throw new Error('cannot display multi dim table: ' + JSON.stringify(types));
    }

    // treat the first column as the label
    // all other columns are treate as series - this only makes sense for queries like
    // select type, min(weight), max(weight) group by type
    const res = this.all;
    this.columns = [];
    this.all = [];

    const cols = {};
    let first = null;
    for (const row of res) {
      for (const key of Object.keys(row)) {
        if (!cols[key]) {
          cols[key] = [];
          if (!first) {
            first = key;
          }
        }
      }
    }
    for (const row of res) {
      for (const [k, v] of Object.entries(row)) {
        cols[k].push(v);
      }
    }
    this.columns = cols[first];
    this.all = [];

    delete cols[first];
    for (const [k, v] of Object.entries(cols)) {
      this.all.push({ data: v, label: k });
    }
  }

  /**
   * check the column datatypes
   */
  checkColumns(table: object[]) {
    const res = {};
    for (const row of table) {
      for (const [k, v] of Object.entries(row)) {
        if (res[k]) {
          if (typeof (v) === 'string') {
            res[k] = 'string';
          }
        } else {
          res[k] = typeof (v);
        }
      }
    }
    return res;
  }
}
