let mapElement;


// =====================================================
// INITIALIZE MAP
// =====================================================

function initializeMap() {

    mapElement = document.getElementById("map");

    mapElement.innerHTML = `
        <div class="map-grid"></div>

        <div class="map-label" style="top: 12px; left: 12px;">
            LOCAL HAWKEYE NETWORK
        </div>

        <div class="map-legend">

            <div class="legend-item">
                <div
                    class="legend-dot"
                    style="background:#4ade80;">
                </div>
                Tourist
            </div>

            <div class="legend-item">
                <div
                    class="legend-dot"
                    style="background:#60a5fa;">
                </div>
                Node
            </div>

            <div class="legend-item">
                <div
                    class="legend-dot"
                    style="background:#f87171;">
                </div>
                SOS
            </div>

        </div>
    `;
}


// =====================================================
// LOAD DASHBOARD
// =====================================================

async function loadDashboard() {

    try {

        const response = await fetch("/network");

        if (!response.ok) {
            throw new Error("Network endpoint unavailable");
        }

        const data = await response.json();

        updateDashboard(data);
        updateMap(data);

    } catch (error) {

        console.log(
            "Waiting for Hawkeye server...",
            error
        );
    }
}


// =====================================================
// UPDATE STATISTICS
// =====================================================

function updateDashboard(data) {

    document.getElementById("tourist-count").textContent =
        data.tourists?.length || 0;

    document.getElementById("node-count").textContent =
        data.nodes?.length || 0;

    document.getElementById("sos-count").textContent =
        data.sos?.length || 0;

    // Total historical GPS records
    document.getElementById("location-count").textContent =
        data.locations?.length || 0;

    updateNodes(data.nodes);
}


// =====================================================
// UPDATE NODE LIST
// =====================================================

function updateNodes(nodes) {

    const nodesList =
        document.getElementById("nodes-list");

    nodesList.innerHTML = "";

    if (!nodes || nodes.length === 0) {

        nodesList.innerHTML =
            '<div class="empty">No nodes registered</div>';

        return;
    }


    nodes.forEach(node => {

        const element =
            document.createElement("div");

        element.className = "node";

        element.innerHTML = `
            <div>

                <div class="node-name">
                    ${node.node_id}
                </div>

                <div class="node-zone">
                    ${node.zone}
                </div>

            </div>

            <div class="online">
                ● ${node.status}
            </div>
        `;

        nodesList.appendChild(element);
    });
}


// =====================================================
// GPS → MAP PROJECTION
// =====================================================

function projectCoordinates(
    latitude,
    longitude,
    bounds
) {

    const lonRange =
        bounds.maxLon - bounds.minLon;

    const latRange =
        bounds.maxLat - bounds.minLat;


    const x =
        lonRange === 0
            ? 50
            : ((longitude - bounds.minLon) /
                lonRange) * 100;


    const y =
        latRange === 0
            ? 50
            : 100 -
            ((latitude - bounds.minLat) /
                latRange) * 100;


    return {
        x,
        y
    };
}


// =====================================================
// CREATE MAP MARKER
// =====================================================

function createMarker(
    x,
    y,
    type,
    label,
    popup
) {

    const marker =
        document.createElement("div");

    marker.className =
        `map-marker marker-${type}`;

    marker.style.left =
        `${x}%`;

    marker.style.top =
        `${y}%`;


    marker.innerHTML = `
        <div class="marker-dot"></div>

        <div class="marker-label">
            ${label}
        </div>
    `;


    marker.title = popup;


    marker.onclick = () => {

        alert(popup);
    };


    mapElement.appendChild(marker);
}


// =====================================================
// UPDATE MAP
// =====================================================

function updateMap(data) {

    // Remove previous markers
    document
        .querySelectorAll(".map-marker")
        .forEach(marker => marker.remove());


    const allPoints = [];


    // =================================================
    // IMPORTANT:
    // Use ONLY the latest position of each tourist.
    // Historical locations remain stored in SQLite.
    // =================================================

    const latestLocations =
        data.latest_locations ||
        getLatestLocationsFromHistory(data.locations);


    // Tourist positions
    latestLocations.forEach(location => {

        allPoints.push({
            latitude: location.latitude,
            longitude: location.longitude
        });
    });


    // Node positions
    data.nodes.forEach(node => {

        allPoints.push({
            latitude: node.latitude,
            longitude: node.longitude
        });
    });


    // SOS positions
    data.sos.forEach(alert => {

        allPoints.push({
            latitude: alert.latitude,
            longitude: alert.longitude
        });
    });


    if (allPoints.length === 0) {
        return;
    }


    // =================================================
    // CALCULATE MAP BOUNDS
    // =================================================

    const latitudes =
        allPoints.map(point => point.latitude);

    const longitudes =
        allPoints.map(point => point.longitude);


    const minLat =
        Math.min(...latitudes);

    const maxLat =
        Math.max(...latitudes);

    const minLon =
        Math.min(...longitudes);

    const maxLon =
        Math.max(...longitudes);


    // Add padding
    const latPadding =
        Math.max(
            (maxLat - minLat) * 0.25,
            0.001
        );

    const lonPadding =
        Math.max(
            (maxLon - minLon) * 0.25,
            0.001
        );


    const bounds = {

        minLat:
            minLat - latPadding,

        maxLat:
            maxLat + latPadding,

        minLon:
            minLon - lonPadding,

        maxLon:
            maxLon + lonPadding
    };


    // =================================================
    // DRAW NODES
    // =================================================

    data.nodes.forEach(node => {

        const position =
            projectCoordinates(
                node.latitude,
                node.longitude,
                bounds
            );


        createMarker(

            position.x,
            position.y,

            "node",

            node.node_id,

            `
Node: ${node.node_id}

Zone: ${node.zone}

Status: ${node.status}

GPS: ${node.latitude}, ${node.longitude}
            `
        );
    });


    // =================================================
    // DRAW LIVE TOURIST POSITIONS
    // =================================================

    latestLocations.forEach(location => {

        const position =
            projectCoordinates(
                location.latitude,
                location.longitude,
                bounds
            );


        createMarker(

            position.x,
            position.y,

            "tourist",

            location.hawkeye_id,

            `
Tourist: ${location.hawkeye_id}

LIVE GPS

Latitude: ${location.latitude}

Longitude: ${location.longitude}

Last Update:
${formatTimestamp(location.timestamp)}
            `
        );
    });


    // =================================================
    // DRAW SOS ALERTS
    // =================================================

    data.sos.forEach(alert => {

        const position =
            projectCoordinates(
                alert.latitude,
                alert.longitude,
                bounds
            );


        createMarker(

            position.x,
            position.y,

            "sos",

            "🚨 SOS",

            `
🚨 SOS ALERT

Tourist: ${alert.hawkeye_id}

GPS:
${alert.latitude}, ${alert.longitude}

Time:
${formatTimestamp(alert.timestamp)}
            `
        );
    });
}


// =====================================================
// FALLBACK:
// GET LATEST LOCATION FROM HISTORICAL DATA
// =====================================================

function getLatestLocationsFromHistory(locations) {

    if (!locations || locations.length === 0) {
        return [];
    }


    const latest = {};


    locations.forEach(location => {

        const existing =
            latest[location.hawkeye_id];


        if (
            !existing ||
            new Date(location.timestamp) >
            new Date(existing.timestamp)
        ) {

            latest[location.hawkeye_id] =
                location;
        }
    });


    return Object.values(latest);
}


// =====================================================
// FORMAT TIMESTAMP
// =====================================================

function formatTimestamp(timestamp) {

    if (!timestamp) {
        return "Unknown";
    }


    const date =
        new Date(timestamp);


    if (isNaN(date.getTime())) {
        return timestamp;
    }


    return date.toLocaleString();
}


// =====================================================
// START
// =====================================================

initializeMap();

loadDashboard();


// =====================================================
// LIVE REFRESH
// =====================================================

setInterval(
    loadDashboard,
    5000
);