document.addEventListener('DOMContentLoaded', () => {
    // --- Map Initialization ---
    // Create a map instance centered globally, zoom level 2
    const map = L.map('map').setView([20, 0], 2);

    // Add OpenStreetMap tile layer
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
    }).addTo(map);

    // --- Data Fetching & Processing ---
    // URL for USGS GeoJSON feed (M2.5+ earthquakes in the past week)
    const earthquakeUrl = 'https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_week.geojson';

    fetch(earthquakeUrl)
        .then(response => {
            if (!response.ok) {
                throw new Error(`HTTP error! Status: ${response.status}`);
            }
            return response.json(); // Parse GeoJSON data
        })
        .then(data => {
            console.log("Fetched earthquake data:", data); // Log data for inspection
            L.geoJSON(data, {
                pointToLayer: createCircleMarker, // Use our function to create markers
                onEachFeature: addPopup // Use our function to add popups
            }).addTo(map);
        })
        .catch(error => {
            console.error('Error fetching earthquake data:', error);
            // Display an error message to the user (optional)
            const legendDiv = document.getElementById('legend');
            if (legendDiv) {
                legendDiv.innerHTML += '<p style="color: red;">Could not load earthquake data.</p>';
            }
        });

    // --- Helper Functions ---

    // Function to determine marker color based on magnitude
    function getColor(magnitude) {
        // Colors based on PDF instructions
        if (magnitude >= 5.0) {
            return "#d73027"; // Red
        } else if (magnitude >= 4.0) {
            return "#fee08b"; // Yellow
        } else {
            return "#4575b4"; // Blue
        }
        // Using HEX colors for better definition
    }

    // Function to determine marker radius based on magnitude
    function getRadius(magnitude) {
        // Simple scaling - adjust as needed for visual preference
        if (magnitude >= 5.0) {
            return 12; // Largest
        } else if (magnitude >= 4.0) {
            return 8; // Medium
        } else {
            return 5; // Small
        }
        // Can also use a continuous function like: return magnitude * 2;
    }

    // Function to create styled circle markers for each point feature
    function createCircleMarker(feature, latlng) {
        const magnitude = feature.properties.mag;
        return L.circleMarker(latlng, {
            radius: getRadius(magnitude),
            fillColor: getColor(magnitude),
            color: "#000", // Border color
            weight: 0.5, // Border weight
            opacity: 1,
            fillOpacity: 0.8
        });
    }

    // Function to add a popup to each feature
    function addPopup(feature, layer) {
        if (feature.properties) {
            const props = feature.properties;
            const time = new Date(props.time).toLocaleString(); // Format timestamp
            layer.bindPopup(`<h3>${props.place || 'Location N/A'}</h3><hr>` +
                            `<p>Magnitude: ${props.mag}</p>` +
                            `<p>Time: ${time}</p>` +
                            `<p><a href="${props.url}" target="_blank">More details (USGS)</a></p>`);
        }
    }

    // --- Legend Creation ---
    function createLegend() {
        const legendDiv = document.getElementById('legend');
        if (!legendDiv) return; // Exit if legend div not found

        const grades = [0, 4.0, 5.0]; // Magnitude thresholds
        const labels = ['< 4.0 (Minor)', '4.0 - 4.9 (Light)', '≥ 5.0 (Moderate+)'];

        // Loop through intervals and generate a label with a colored square for each interval
        for (let i = 0; i < grades.length; i++) {
            const magnitude = grades[i] + (i === 0 ? 0 : 0.1); // Get a representative magnitude for color
            const color = getColor(magnitude);
            const labelText = labels[i];

            // Use innerHTML to add items
             legendDiv.innerHTML +=
                '<div class="legend-item">' +
                    '<span class="legend-color" style="background-color:' + color + '"></span> ' +
                    '<span class="legend-text">' + labelText + '</span>' +
                '</div>';
        }
    }

    createLegend(); // Call the function to build the legend

}); // End of DOMContentLoaded listener