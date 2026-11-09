/*
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
var showControllersOnly = false;
var seriesFilter = "";
var filtersOnlySampleSeries = true;

/*
 * Add header in statistics table to group metrics by category
 * format
 *
 */
function summaryTableHeader(header) {
    var newRow = header.insertRow(-1);
    newRow.className = "tablesorter-no-sort";
    var cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Requests";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 3;
    cell.innerHTML = "Executions";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 7;
    cell.innerHTML = "Response Times (ms)";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 1;
    cell.innerHTML = "Throughput";
    newRow.appendChild(cell);

    cell = document.createElement('th');
    cell.setAttribute("data-sorter", false);
    cell.colSpan = 2;
    cell.innerHTML = "Network (KB/sec)";
    newRow.appendChild(cell);
}

/*
 * Populates the table identified by id parameter with the specified data and
 * format
 *
 */
function createTable(table, info, formatter, defaultSorts, seriesIndex, headerCreator) {
    var tableRef = table[0];

    // Create header and populate it with data.titles array
    var header = tableRef.createTHead();

    // Call callback is available
    if(headerCreator) {
        headerCreator(header);
    }

    var newRow = header.insertRow(-1);
    for (var index = 0; index < info.titles.length; index++) {
        var cell = document.createElement('th');
        cell.innerHTML = info.titles[index];
        newRow.appendChild(cell);
    }

    var tBody;

    // Create overall body if defined
    if(info.overall){
        tBody = document.createElement('tbody');
        tBody.className = "tablesorter-no-sort";
        tableRef.appendChild(tBody);
        var newRow = tBody.insertRow(-1);
        var data = info.overall.data;
        for(var index=0;index < data.length; index++){
            var cell = newRow.insertCell(-1);
            cell.innerHTML = formatter ? formatter(index, data[index]): data[index];
        }
    }

    // Create regular body
    tBody = document.createElement('tbody');
    tableRef.appendChild(tBody);

    var regexp;
    if(seriesFilter) {
        regexp = new RegExp(seriesFilter, 'i');
    }
    // Populate body with data.items array
    for(var index=0; index < info.items.length; index++){
        var item = info.items[index];
        if((!regexp || filtersOnlySampleSeries && !info.supportsControllersDiscrimination || regexp.test(item.data[seriesIndex]))
                &&
                (!showControllersOnly || !info.supportsControllersDiscrimination || item.isController)){
            if(item.data.length > 0) {
                var newRow = tBody.insertRow(-1);
                for(var col=0; col < item.data.length; col++){
                    var cell = newRow.insertCell(-1);
                    cell.innerHTML = formatter ? formatter(col, item.data[col]) : item.data[col];
                }
            }
        }
    }

    // Add support of columns sort
    table.tablesorter({sortList : defaultSorts});
}

$(document).ready(function() {

    // Customize table sorter default options
    $.extend( $.tablesorter.defaults, {
        theme: 'blue',
        cssInfoBlock: "tablesorter-no-sort",
        widthFixed: true,
        widgets: ['zebra']
    });

    var data = {"OkPercent": 98.38138095907749, "KoPercent": 1.6186190409225145};
    var dataset = [
        {
            "label" : "FAIL",
            "data" : data.KoPercent,
            "color" : "#FF6347"
        },
        {
            "label" : "PASS",
            "data" : data.OkPercent,
            "color" : "#9ACD32"
        }];
    $.plot($("#flot-requests-summary"), dataset, {
        series : {
            pie : {
                show : true,
                radius : 1,
                label : {
                    show : true,
                    radius : 3 / 4,
                    formatter : function(label, series) {
                        return '<div style="font-size:8pt;text-align:center;padding:2px;color:white;">'
                            + label
                            + '<br/>'
                            + Math.round10(series.percent, -2)
                            + '%</div>';
                    },
                    background : {
                        opacity : 0.5,
                        color : '#000'
                    }
                }
            }
        },
        legend : {
            show : true
        }
    });

    // Creates APDEX table
    createTable($("#apdexTable"), {"supportsControllersDiscrimination": true, "overall": {"data": [0.3337083391927999, 500, 1500, "Total"], "isController": false}, "titles": ["Apdex", "T (Toleration threshold)", "F (Frustration threshold)", "Label"], "items": [{"data": [0.0724981738495252, 500, 1500, "Delete"], "isController": false}, {"data": [0.0997624703087886, 500, 1500, "Post-aceite"], "isController": false}, {"data": [0.8892773892773893, 500, 1500, "Put-consulta"], "isController": false}, {"data": [0.0563285774826953, 500, 1500, "Post"], "isController": false}, {"data": [0.7391304347826086, 500, 1500, "Put-aceite"], "isController": false}, {"data": [0.7451408604498799, 500, 1500, "Get"], "isController": false}, {"data": [0.8583333333333333, 500, 1500, "Delete-consulta"], "isController": false}, {"data": [0.7129629629629629, 500, 1500, "Delete-aceite"], "isController": false}, {"data": [0.6702700735240591, 500, 1500, "GetList"], "isController": false}, {"data": [0.07786109493302272, 500, 1500, "Put"], "isController": false}, {"data": [0.836890243902439, 500, 1500, "Post-consulta"], "isController": false}]}, function(index, item){
        switch(index){
            case 0:
                item = item.toFixed(3);
                break;
            case 1:
            case 2:
                item = formatDuration(item);
                break;
        }
        return item;
    }, [[0, 0]], 3);

    // Create statistics table
    createTable($("#statisticsTable"), {"supportsControllersDiscrimination": true, "overall": {"data": ["Total", 71110, 1151, 1.6186190409225145, 4198.963605681334, 0, 22568, 3477.0, 7873.9000000000015, 8505.95, 9631.950000000008, 783.9786558475922, 69141.80967849406, 1233.6962813106368], "isController": false}, "titles": ["Label", "#Samples", "FAIL", "Error %", "Average", "Min", "Max", "Median", "90th pct", "95th pct", "99th pct", "Transactions/s", "Received", "Sent"], "items": [{"data": ["Delete", 13690, 108, 0.7888970051132214, 5976.707669832013, 28, 13418, 6596.0, 8998.9, 9589.899999999998, 11020.09, 171.777755470789, 912.2033723265597, 498.40414081321524], "isController": false}, {"data": ["Post-aceite", 421, 368, 87.41092636579573, 259.66270783847966, 0, 1125, 263.0, 302.0, 722.9999999999991, 1089.6799999999998, 6.179907227996008, 14.280556526517822, 0.2492009057013681], "isController": false}, {"data": ["Put-consulta", 429, 45, 10.48951048951049, 239.83449883449873, 0, 1350, 176.0, 405.0, 1255.0, 1313.0999999999995, 35.031847133757964, 21.37982874510044, 6.859382655560999], "isController": false}, {"data": ["Post", 14158, 421, 2.973583839525357, 7362.051914112173, 0, 22568, 7308.0, 13347.2, 16560.549999999992, 19643.23, 163.8828118669769, 641.3958186833697, 357.8669580743364], "isController": false}, {"data": ["Put-aceite", 46, 1, 2.1739130434782608, 520.6521739130436, 1, 1800, 464.0, 904.4000000000008, 1455.0999999999995, 1800.0, 3.5906642728904847, 1.7473082214503162, 1.111410604168293], "isController": false}, {"data": ["Get", 13737, 0, 0.0, 642.8487297080903, 0, 3410, 375.0, 1914.2000000000044, 2983.0999999999985, 3264.619999999999, 180.71671007972216, 54.35619795366643, 33.17845849119899], "isController": false}, {"data": ["Delete-consulta", 720, 100, 13.88888888888889, 235.52222222222215, 0, 1359, 175.5, 378.79999999999995, 1253.9499999999998, 1346.79, 67.22061432172532, 46.16831192815797, 12.66221641303333], "isController": false}, {"data": ["Delete-aceite", 108, 8, 7.407407407407407, 520.5833333333333, 0, 1635, 483.0, 1124.200000000001, 1344.2, 1632.3, 8.826413860738803, 5.299902694916639, 1.6760225972540046], "isController": false}, {"data": ["GetList", 13737, 1, 0.007279609812914028, 849.5004731746435, 7, 3892, 485.0, 3053.2000000000007, 3377.0, 3643.0, 180.04167813470687, 79609.17575560049, 31.997265691555587], "isController": false}, {"data": ["Put", 13736, 46, 0.33488642981945255, 6661.104251601641, 11, 14801, 7028.0, 9863.0, 10527.3, 11923.669999999993, 172.53680349696026, 791.8248966471764, 451.803699112571], "isController": false}, {"data": ["Post-consulta", 328, 53, 16.158536585365855, 231.57926829268288, 0, 1353, 175.5, 382.10000000000025, 1250.65, 1340.6799999999998, 39.229757206075824, 28.873709596639156, 7.1948630546585335], "isController": false}]}, function(index, item){
        switch(index){
            // Errors pct
            case 3:
                item = item.toFixed(2) + '%';
                break;
            // Mean
            case 4:
            // Mean
            case 7:
            // Median
            case 8:
            // Percentile 1
            case 9:
            // Percentile 2
            case 10:
            // Percentile 3
            case 11:
            // Throughput
            case 12:
            // Kbytes/s
            case 13:
            // Sent Kbytes/s
                item = item.toFixed(2);
                break;
        }
        return item;
    }, [[0, 0]], 0, summaryTableHeader);

    // Create error table
    createTable($("#errorsTable"), {"supportsControllersDiscrimination": false, "titles": ["Type of error", "Number of errors", "% in errors", "% in all samples"], "items": [{"data": ["Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 576, 50.04344048653345, 0.810012656447757], "isController": false}, {"data": ["Non HTTP response code: org.apache.http.conn.HttpHostConnectException", 575, 49.95655951346655, 0.8086063844747574], "isController": false}]}, function(index, item){
        switch(index){
            case 2:
            case 3:
                item = item.toFixed(2) + '%';
                break;
        }
        return item;
    }, [[1, 1]]);

        // Create top5 errors by sampler
    createTable($("#top5ErrorsBySamplerTable"), {"supportsControllersDiscrimination": false, "overall": {"data": ["Total", 71110, 1151, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 576, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException", 575, "", "", "", "", "", ""], "isController": false}, "titles": ["Sample", "#Samples", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors", "Error", "#Errors"], "items": [{"data": ["Delete", 13690, 108, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException", 108, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["Post-aceite", 421, 368, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 368, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["Put-consulta", 429, 45, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 45, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["Post", 14158, 421, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException", 421, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["Put-aceite", 46, 1, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 1, "", "", "", "", "", "", "", ""], "isController": false}, {"data": [], "isController": false}, {"data": ["Delete-consulta", 720, 100, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 100, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["Delete-aceite", 108, 8, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 8, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["GetList", 13737, 1, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 1, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["Put", 13736, 46, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException", 46, "", "", "", "", "", "", "", ""], "isController": false}, {"data": ["Post-consulta", 328, 53, "Non HTTP response code: org.apache.http.conn.HttpHostConnectException/Non HTTP response message: Connect to 127.0.0.1:8081 [/127.0.0.1] failed: Connection refused: getsockopt", 53, "", "", "", "", "", "", "", ""], "isController": false}]}, function(index, item){
        return item;
    }, [[0, 0]], 0);

});
